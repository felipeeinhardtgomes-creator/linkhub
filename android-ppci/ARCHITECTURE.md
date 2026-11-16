# Levantamento PPCI — Arquitetura

App Android (Kotlin + Jetpack Compose) para levantamento em campo e orçamento de PPCI sobre plantas
A2/A1/A0 exportadas do AutoCAD. Funciona 100% offline.

> **Estado atual da verificação**
> - **Verificado de verdade (JVM):** `TileMath`, `Summary` e `CsvExporter` foram compilados e testados fora do Android
>   (9 testes passando: nível por zoom, cobertura de tiles, agrupamento, escape/BOM do CSV).
> - **Escrito mas NÃO compilado:** todo o código que usa Android, Compose, Room e Hilt. O ambiente de autoria não
>   alcança o Google Maven nem o Android SDK. O primeiro passo no Android Studio é sincronizar o Gradle
>   (o wrapper já está em `gradlew`) e corrigir o que o compilador apontar.
> - **Não executado em dispositivo:** desempenho em prancha A0 real, câmera, compartilhamento. Os testes
>   instrumentados (`androidTest/`) existem para rodar nesse momento, ver seção 8.

## 1. Decisões principais

| Tema | Decisão | Por quê |
|---|---|---|
| UI | Jetpack Compose + Material 3 | Um único modelo de estado para planta, pinos e menus. |
| Visualizador | **Motor de tiles próprio em Compose** sobre `PdfRenderer` | Zoom até 15x com vetor re-rasterizado por região; pinos e gestos no mesmo sistema de coordenadas. |
| Persistência | Room (SQLite) + arquivos em `filesDir` | Offline por construção. Esquema exportado (`schemas/`) para testar migrações. |
| DI | Hilt (KSP) | Padrão do ecossistema; ViewModel injetável. |
| Fotos | `TakePicture` + `FileProvider` + Coil; reduzidas a 1600 px com EXIF aplicado | Sem CameraX. ~4 MB viram ~300 KB por foto. |
| Preferências | DataStore (empresa e RT) | Lembrados entre visitas. |
| Backup | `.zip` autocontido (kotlinx.serialization + `java.util.zip`) | Troca de aparelho e envio da visita ao escritório. |
| Relatório PDF | `android.graphics.pdf.PdfDocument` | Sem dependência; controle total de paginação. |
| CSV | Gerado à mão (`;` + BOM UTF-8) | Abre direto no Excel em português. |
| Leitura do carimbo | PDFBox-Android (texto vetorial) → ML Kit OCR offline (fallback) | PDFs do AutoCAD costumam ter texto real; OCR cobre carimbo explodido ou escaneado. |
| Permissões | **Sem `INTERNET`** | O "100% offline" fica verificável no manifesto. |

### Por que não `SubsamplingScaleImageView`
SSIV decodifica **imagens** em regiões e é excelente para raster, mas uma planta de CAD é vetorial: para manter
as linhas finas nítidas a 15x é preciso que o Pdfium rasterize cada região **na escala final**, não que se
amostre um raster pré-gerado. Dá para plugar um `ImageRegionDecoder` sobre `PdfRenderer`, porém os pinos
ficariam em uma `View` à parte, sincronizados por callbacks. O motor próprio mantém **uma** fonte de verdade
para zoom, pan e pinos. Se o desempenho do Compose Canvas ficar aquém em aparelhos fracos, SSIV com decoder
customizado é o plano B (a camada `PdfPageSource` é reaproveitada).

### Por que `PdfRenderer` e não PDFium empacotado
Zero dependência nativa e APK menor. Trade-off: `PdfRenderer` não é thread-safe e abre uma página por vez
(tratado, ver 3). Se a velocidade em pranchas muito densas não bastar, trocar por `pdfiumandroid` mexe só em
`PdfPageSource`.

## 2. Camadas e pacotes (`br.com.ppci.levantamento`)

```
domain/   Category, ItemStatus, Counts            (regras de negócio puras)
data/     Room: SurveyEntity, MarkerEntity, DAOs, SurveyRepository, SettingsStore (DataStore)
backup/   BackupManager (export/import .zip)
util/     PhotoUtils (reduzir foto, decodificar imagem com EXIF)
pdf/      TileMath (puro, testável), PdfPageSource, TileCache
export/   Summary (agrupamento), CsvExporter, ReportPdfWriter, TitleBlockReader
ui/       Theme, home/HomeScreen, viewer/{PlanViewer, ViewerViewModel, ViewerScreen, MarkerSheet}
di/       AppModule (Room)
```

Fluxo de dados: `Room → Flow → ViewModel (StateFlow) → Compose`. A fonte do PDF (`PdfPageSource`) vive no
ViewModel, sobrevive a rotação e é fechada em `onCleared`.

## 3. Motor de tiles (`pdf/`)

**Pirâmide.** O nível `L` representa a página inteira com largura `1024 × 2^L` px, fatiada em tiles de 512 px.
`MAX_LEVEL = 4` → 16 384 px, suficiente para zoom 15x em telas de ~1100 px.

**Escolha do nível.** `levelFor(contentWidthPx)` = menor `L` com resolução ≥ largura do conteúdo na tela
(`fitWidth × zoom`). Resolução de tela = resolução do tile: nítido sem desperdício.

**Memória (evita `OutOfMemoryError`).**
- Nunca existe um bitmap da página inteira em alta resolução. Só: um *base* de 1024 px + tiles visíveis
  (≈ 12 a 20 tiles de 1 MB) + cache.
- `TileCache` é um `LruCache` limitado por **bytes** (1/6 da heap, entre 48 e 192 MB).
- Tiles de borda têm tamanho menor que 512 (sem pixels desperdiçados).

**Renderização.** `PdfPageSource.renderRegion` cria o bitmap do tile e chama `page.render(bmp, null, matrix, …)` com
`Matrix = translate(-região) · scale(tilePx / regiãoPt)`. O Pdfium rasteriza o vetor direto na escala do tile.
Fundo preenchido de branco (PDFs de CAD são transparentes).

**Concorrência.**
- `PdfRenderer` roda numa thread dedicada (`pdf-render`, prioridade abaixo da UI), com a página aberta durante
  toda a sessão (reabrir por tile seria caro).
- `snapshotFlow { tiles visíveis } → distinctUntilChanged → debounce(60 ms) → collectLatest`: durante um gesto
  não se pede nada; ao assentar, pedidos obsoletos são cancelados. Tiles mais perto do centro vêm primeiro.
- Cancelamento é cooperativo: um tile já em renderização termina; os seguintes não começam.

**Desenho.** Base → níveis `1..L` em ordem crescente (o mais nítido por cima; níveis grossos cobrem lacunas
enquanto o fino carrega) → pinos. Tiles recebem +1 px para evitar frestas.

**Gestos.** `detectTransformGestures` (pinça + arrasto), duplo toque = zoom 2,5x, toque = marcar/editar.
O ponto sob o dedo permanece fixo ao dar zoom; o deslocamento é limitado para a planta nunca sair da tela.

## 4. Coordenadas normalizadas

Todo pino é salvo como `(x, y)` em `0f..1f` da **página** (origem no canto superior esquerdo). A conversão está
concentrada em `ViewerState`:

```
tela  = offset + (x · contentW, y · contentH)       contentW = fitWidth · zoom,  contentH = contentW · aspect
norma = (tela − offset) / (contentW, contentH)
```

Consequências: zoom, rotação do aparelho e telas diferentes não deslocam pinos; o mesmo `(x, y)` é usado na
tela, no relatório PDF (planta anotada) e em qualquer exportação futura. Tamanho do pino é constante em dp.
O hit-test é feito em pixels de tela (raio de 28 dp), então tocar perto de um pino o seleciona em qualquer zoom.

## 5. Modelo de dados

```
survey(id, company, client, site, technician, createdAt, pdfPath, pageCount)
marker(id, surveyId → survey ON DELETE CASCADE, page, x, y, category, spec, status, note, photoPath, createdAt)
```

- **Um só formato de planta:** PDF é copiado em streaming para `filesDir/plans/`; PNG/JPG é convertido em PDF
  de 1 página (lado maior ≤ 4096 px, EXIF aplicado). O original nunca é alterado e o resto do app só conhece PDF.
- **Várias pranchas:** `page` (base 0) em cada marcador; a tela mostra só os pinos da prancha atual, com seletor
  `Prancha n / N`. A **numeração (#1, #2…) é global**: posição na lista completa ordenada por `id`. Excluir um
  item renumera os seguintes; tela, CSV e PDF concordam porque usam a mesma lista.
- Fotos em `filesDir/photos/`. Excluir item ou levantamento apaga os arquivos.
- Enums gravados como texto (Room ≥ 2.3).
- **Esquema:** a versão 1 ainda não foi distribuída, então mudanças entram sem migração. **A partir do primeiro
  release**, toda alteração exige `Migration` + teste (`exportSchema = true` grava `app/schemas/`; versione essa pasta).

## 6. Fluxo de campo

1. **Home:** lista de levantamentos anteriores (abrir ou excluir com confirmação); importa PDF/imagem (Storage Access Framework) ou backup `.zip`; `TitleBlockReader` sugere Cliente / Obra / RT; empresa e RT vêm da última visita.
2. **Viewer:** toque em um símbolo → `MarkerSheet` (toque **longo** cria item novo mesmo colado a outro pino) (bottom sheet): categoria → especificação (chips ou texto) →
   observação/foto → **toque em um status salva**. "NÃO TEM" é o maior botão (é o que vira orçamento);
   a última categoria usada vem pré-selecionada.
3. **Barra superior:** `Orçar: N itens | Existentes: M` em tempo real (`Counts`, derivado do `Flow` de marcadores).
4. **Exportar:** menu → PDF / CSV / backup; abre o seletor de compartilhamento do Android (e-mail, WhatsApp, Drive,
   salvar em arquivos) via `FileProvider`. O arquivo é gerado offline e só o compartilhamento depende de rede.

## 7. Exportação

- **CSV item a item:** `Item;Categoria;Especificação;Status;Quantidade;Observação` (Quantidade = 1 por pino).
- **CSV agrupado:** uma linha por categoria + especificação + status, com a lista de pinos (`#3 #7 #9`).
  Chave de agrupamento ignora maiúsculas e espaços nas pontas da especificação.
- **PDF A4:** (1) cabeçalho, KPIs, resumo "a orçar" (Não tem × Irregular) e "existentes"; (2) planta anotada, **uma por prancha com marcadores**: a
  página renderizada a 2400 px + pinos desenhados com as mesmas coordenadas normalizadas; (3) tabela item a item
  com paginação; (4) anexo fotográfico (fotos decodificadas com `inSampleSize` para não estourar memória).

- **Backup `.zip`:** `survey.json` (dados e marcadores normalizados) + `plan.pdf` + `photos/`. A importação cria
  **sempre um levantamento novo** (nunca sobrescreve), valida o PDF e protege contra zip-slip (só nomes fixos).

## 8. Testes e verificação

| Teste | Onde | Estado |
|---|---|---|
| `TileMathTest` (nível por zoom, cobertura exata, poucos tiles, fora da página) | JVM | **executado: passa** |
| `SummaryCsvTest` (agrupamento ignorando caixa/espaços, ordem, BOM, escape de `;` e `"`, pinos) | JVM | **executado: passa** |
| `SurveyDaoTest` (coordenadas/página, cascata, contagens da lista) | `androidTest` (Room em memória) | escrito, não executado |
| `MarkerSheetTest` (chip + toque em NÃO TEM salva com espec. e status) | `androidTest` (Compose) | escrito, não executado |
| `TileRenderBenchmarkTest` (A1 sintética com 40 000 linhas, tile no zoom máximo) | `androidTest` | escrito, não executado; mede em `adb logcat -s TileBench` |

Para rodar: `./gradlew testDebugUnitTest` e `./gradlew connectedDebugAndroidTest` (tablet/emulador).
Não há teste de migração ainda porque só existe a versão 1 do esquema.

## 9. Riscos conhecidos e o que só um dispositivo responde

1. **Compilação.** Pode haver ajustes de assinatura de API (Compose BOM, Room, Hilt). Versões em `libs.versions.toml` devem ser atualizadas pelo Android Studio.
2. **Tempo de tile em CAD denso (A0).** O Pdfium percorre o conteúdo da página para cada tile. Rode o `TileRenderBenchmarkTest` e, depois, uma prancha real. Mitigações já existentes: base + níveis grossos cobrem lacunas e o cache evita repetição. Plano B = SSIV com decoder customizado, ou PDFium empacotado (só `PdfPageSource` muda).
3. **PDF escaneado:** o raster embutido limita a nitidez, independente do motor.
4. **Símbolos muito próximos:** o toque seleciona o pino mais próximo (raio de 24 dp); o toque longo sempre cria item novo. Se em campo isso ainda atrapalhar, o ajuste é aumentar o zoom mínimo para marcar ou mostrar um seletor quando houver dois pinos no raio.
5. **Textos:** a interface está em `strings.xml` (pt-BR). Rótulos de domínio usados nos documentos (categorias, status, cabeçalhos de CSV/PDF) ficam no código de propósito, porque são terminologia do orçamento e não texto de tela; para traduzir, mova-os junto.
6. **Foto da câmera:** o arquivo grande existe por instantes antes de ser reduzido; se o app for encerrado nesse intervalo fica uma foto grande órfã (sem impacto funcional).
7. **Backup:** exporta um levantamento por vez; não há backup automático em nuvem (de propósito, o app é offline).

## 10. O que as plantas reais mostraram

Análise de 3 pranchas A0 reais (AutoCAD 2018/2020/2025, PPCI de uma unidade industrial e de um galpão comercial):

| Achado | Consequência no app |
|---|---|
| **Todas A0, 2384 × 3370 pt, com `/Rotate 270`** (retrato no arquivo, paisagem na tela) | O app usa `page.width/height` do `PdfRenderer`, que já considera a rotação. **Falta confirmar em aparelho:** `RealPlansTest` falha se a página vier em retrato. |
| **Vetoriais**, 1 página, 450–750 KB | Perfeito para renderização por região. Em poppler (servidor), um tile 512 px a 15x levou 44–160 ms; em tablet espere várias vezes isso. |
| **Cliente, endereço e responsável NÃO são texto**: o AutoCAD plotou a fonte de traço única como linhas | O caminho PDFBox quase nunca preenche o carimbo. OCR passou a ser o caminho principal (região ampla, 3000 px) e há "Ler carimbo desta tela" (menu de exportar) para o técnico aproximar o zoom e ler na hora. |
| **O carimbo muda de lugar**: canto inferior direito em duas pranchas; no meio-direita em outra (folha com margem grande) | Região do OCR cobre a metade direita da folha, não só o canto. |
| **As legendas trazem a nomenclatura real** (PQS BC/ABC, CO2 BC, AP A, ESP AB, sobre rodas; hidrante 1/2 saídas + mangotinho; botoeira quebra-vidro, sirene, sensor de fumaça/temperatura; balizamento; barra antipânico, porta corta-fogo, válvula de bloqueio, reserva técnica) | Os chips de especificação rápida (`Model.kt`) usam esses nomes. |
| **Centenas de símbolos por prancha** | Marcar um a um é lento. Ideia, ainda não implementada: detectar os símbolos pela cor/forma, ou marcar em lote por região. |

O interpretador do carimbo (`TitleBlockParser`) é testado em JVM com trechos desses carimbos (rótulos sem dois-pontos, `RESP. TECNICO:NOME` colado, CREA em outra linha, legenda sem falsos positivos).
**O OCR em si (ML Kit) não foi testado em fonte de traço fina**, só o interpretador. Esse é o próximo teste em aparelho.

### Premissas que NÃO podem ser assumidas (correção do uso real)

- **O cliente pode não estar na planta**, e o carimbo pode não ser legível. Por isso Cliente, Obra e Responsável são **opcionais** em todo o fluxo: o botão "Iniciar" só exige a planta; a leitura do carimbo roda em **segundo plano** depois da importação, só preenche campos vazios e nunca atrasa nem bloqueia o técnico. Relatório e CSV mostram "—" nos campos vazios, e os nomes de arquivo caem em "obra".
- **A planta geralmente não tem legenda.** A lista de especificações do menu de marcação não pode depender dela: os chips mostram **primeiro o que o próprio técnico já usou naquela categoria** (as 8 especificações mais frequentes em todos os levantamentos, por contagem e recência) e depois nomes padrão de mercado (`Model.kt`). Em poucas visitas o catálogo passa a refletir o vocabulário real da empresa, sem cadastro prévio.
