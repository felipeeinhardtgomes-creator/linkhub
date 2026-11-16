# Levantamento PPCI — Arquitetura

App Android (Kotlin + Jetpack Compose) para levantamento em campo e orçamento de PPCI sobre plantas
A2/A1/A0 exportadas do AutoCAD. Funciona 100% offline.

> **Estado atual:** o código foi escrito sem Android SDK disponível no ambiente de autoria, então **ainda não foi
> compilado nem executado em dispositivo**. O primeiro passo no Android Studio é sincronizar o Gradle, rodar
> `TileMathTest` (JVM) e abrir uma prancha A1 real em um tablet. A lista de riscos está no fim.

## 1. Decisões principais

| Tema | Decisão | Por quê |
|---|---|---|
| UI | Jetpack Compose + Material 3 | Um único modelo de estado para planta, pinos e menus. |
| Visualizador | **Motor de tiles próprio em Compose** sobre `PdfRenderer` | Zoom até 15x com vetor re-rasterizado por região; pinos e gestos no mesmo sistema de coordenadas. |
| Persistência | Room (SQLite) + arquivos em `filesDir` | Offline por construção. Esquema exportado (`schemas/`) para testar migrações. |
| DI | Hilt (KSP) | Padrão do ecossistema; ViewModel injetável. |
| Fotos | `ActivityResultContracts.TakePicture` + `FileProvider` + Coil | Sem CameraX: menos código e funciona bem para foto rápida. |
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
data/     Room: SurveyEntity, MarkerEntity, DAOs, SurveyRepository
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
survey(id, company, client, site, technician, createdAt, pdfPath, pageIndex)
marker(id, surveyId → survey ON DELETE CASCADE, x, y, category, spec, status, note, photoPath, createdAt)
```

- O PDF é **copiado em streaming** para `filesDir/plans/` (o original nunca é alterado) e fotos ficam em
  `filesDir/photos/`. Dados privados do app, fora da galeria.
- **Numeração (#1, #2…)** = posição na lista ordenada por `id`. Excluir um item renumera os seguintes, e a
  tela, o CSV e o PDF sempre concordam porque usam a mesma lista.
- Enums são gravados como texto (Room ≥ 2.3).

## 6. Fluxo de campo

1. **Home:** importa o PDF (Storage Access Framework); `TitleBlockReader` sugere Cliente / Obra / RT; o técnico confere.
2. **Viewer:** toque em um símbolo → `MarkerSheet` (bottom sheet): categoria → especificação (chips ou texto) →
   observação/foto → **toque em um status salva**. "NÃO TEM" é o maior botão (é o que vira orçamento);
   a última categoria usada vem pré-selecionada.
3. **Barra superior:** `Orçar: N itens | Existentes: M` em tempo real (`Counts`, derivado do `Flow` de marcadores).
4. **Exportar:** menu → PDF / CSV; abre o seletor de compartilhamento do Android (e-mail, WhatsApp, Drive,
   salvar em arquivos) via `FileProvider`. O arquivo é gerado offline e só o compartilhamento depende de rede.

## 7. Exportação

- **CSV item a item:** `Item;Categoria;Especificação;Status;Quantidade;Observação` (Quantidade = 1 por pino).
- **CSV agrupado:** uma linha por categoria + especificação + status, com a lista de pinos (`#3 #7 #9`).
  Chave de agrupamento ignora maiúsculas e espaços nas pontas da especificação.
- **PDF A4:** (1) cabeçalho, KPIs, resumo "a orçar" (Não tem × Irregular) e "existentes"; (2) planta anotada: a
  página renderizada a 2400 px + pinos desenhados com as mesmas coordenadas normalizadas; (3) tabela item a item
  com paginação; (4) anexo fotográfico (fotos decodificadas com `inSampleSize` para não estourar memória).

## 8. Testes e verificação

- `TileMathTest` (JVM): nível por zoom, cobertura exata da página, poucos tiles para viewport pequeno, viewport fora da página.
- A fazer: teste instrumentado de migração Room (usa `room-testing`), teste de UI do `MarkerSheet`, e
  benchmark de tempo de tile com uma prancha A0 real.

## 9. Riscos conhecidos e próximos passos

1. **Não compilado ainda.** Pode haver ajustes de assinatura de API (Compose BOM, Room, Hilt). Versões em `libs.versions.toml` devem ser atualizadas pelo Android Studio.
2. **Tempo de tile em CAD denso.** O Pdfium percorre o conteúdo da página para cada tile; em A0 com milhares de camadas pode levar centenas de ms. Mitigações: base + níveis grossos já cobrem, e o cache evita repetição. Medir antes de otimizar; plano B = SSIV/PDFium.
3. **Imagem de PDF escaneado:** o tamanho do raster embutido limita a nitidez, independente do motor.
4. **PDFs multipágina:** o modelo guarda `pageIndex`, mas a UI abre só a página 0; falta seletor de prancha (os marcadores precisarão de `page`).
5. **Fotos:** o arquivo da câmera é gravado no tamanho original; reduzir (ex.: 1600 px) antes de salvar economiza espaço em visitas longas.
6. **Backup:** `allowBackup=false` por privacidade; falta uma rotina de exportar/importar levantamento completo (zip) para troca de aparelho.
7. **Lista de levantamentos** e reabertura de visitas anteriores (hoje cada início cria um novo).
8. **Textos** estão fixos em português no código; mover para `strings.xml` se houver outros idiomas.
