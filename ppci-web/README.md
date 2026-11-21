# PPCI Web — Levantamento de PPCI em campo (webapp / PWA)

Versão web do app Android `android-ppci`: importar a planta PDF (AutoCAD), marcar no mapa o que **tem / não tem / está irregular**
(placas, luminárias, extintores, hidrantes, SPK, alarmes…), tirar foto e exportar relatório PDF e CSV.
Roda em **Docker no TrueNAS** e é usado pelo navegador no **Windows, Android e iPhone** (instalável como app).

## O que tem

- **Vários usuários.** O administrador cria os usuários na aba **Administração** (login, nome, senha, perfil; redefinir senha, desativar, excluir).
- **Cada usuário vê somente os levantamentos que ele mesmo fez** — regra aplicada no servidor (dados, marcações, PDF da planta e fotos). O administrador também só vê os próprios.
- **Visualizador de planta** com zoom/pan por toque, mouse e roda; renderiza só a região visível na resolução real da tela (prancha A0 não estoura a memória do celular).
- **Marcação em um toque:** categoria → especificação (sugere o que você já usou) → toque no status salva. Foto opcional pela câmera.
- **Funciona offline:** dados e plantas ficam no aparelho; sincroniza com o servidor quando há internet (indicador no topo).
- **Relatório PDF editável:** escolha o **logo da empresa** (biblioteca no aparelho), título e nome da empresa; leva o **nome do usuário** que fez o levantamento.
  Mostra a planta anotada com **marcas vazadas** (o símbolo da planta continua visível dentro do anel) e uma página de **detalhe ampliado de cada marcação** (recorte da planta em alta resolução + foto).
- **CSV** (itens e agrupado) e **backup .zip** de um levantamento — mesmo formato do app Android (dá para levar dados entre os dois).

## Rodar localmente

```bash
cd ppci-web
npm install
npm test                      # 16 testes (lógica + servidor/isolamento entre usuários)
npm run build
PPCI_ADMIN_PASSWORD=troque-me npm start     # http://localhost:3000   (requer Node >= 22.13)
```

Desenvolvimento com hot reload: `npm run dev:server` e, em outro terminal, `npm run dev:client`.

Primeiro acesso: usuário `admin` e a senha de `PPCI_ADMIN_PASSWORD`. Se a variável não existir, uma senha é **gerada e impressa no log** do container. As variáveis só valem quando o banco ainda não tem usuários.

## Docker

```bash
docker compose up --build        # http://localhost:3000  (edite a senha em docker-compose.yml)
```

Tudo que precisa sobreviver (banco SQLite, PDFs e fotos) fica em `/data` — monte um volume/dataset ali.

## TrueNAS SCALE

1. Crie um dataset (ex.: `/mnt/tank/apps/ppci`) e dê o dono ao usuário `apps`: `chown -R 568:568 /mnt/tank/apps/ppci`.
2. **Imagem:** o TrueNAS só faz *pull*. O workflow `.github/workflows/ppci-web-publish.yml` publica `ghcr.io/<usuario>/linkhub-ppci:latest` a cada push em `main` que mexa em `ppci-web/` (ou rode-o manualmente em *Actions*). Depois deixe o pacote **público** (ou configure login no TrueNAS). Alternativa sem registry: `docker build -t ppci-web ppci-web && docker save | ssh … docker load`.
3. **Apps → Discover Apps → ⋮ → Install via YAML**, cole `docker-compose.truenas.yml`, ajuste `image:`, a senha do admin e o caminho do dataset.
4. Acesse `http://<ip-do-truenas>:3060`.

**Backup:** faça snapshot do dataset (ZFS). O arquivo `ppci.db` + `blobs/` é todo o estado.

## HTTPS é obrigatório para uso em campo

Câmera, instalação como app (iPhone/Android) e modo offline **só funcionam em HTTPS** (exceto `localhost`). Com **Cloudflare Tunnel**:
crie um túnel apontando o hostname (ex.: `ppci.seudominio.com`) para `http://<ip-do-truenas>:3060`. O servidor já confia no proxy (`trust proxy`) e marca o cookie de sessão como `Secure` sob HTTPS.
Como o endereço fica público, use senhas fortes; o login tem limite de tentativas.

Instalar no celular: Android/Chrome → menu → *Instalar app*; iPhone/Safari → Compartilhar → *Adicionar à Tela de Início*.
**Abra cada levantamento uma vez com internet** antes de ir à obra (o PDF da planta é baixado para o aparelho) — o app também baixa as plantas sozinho após sincronizar.

## Segurança (resumo)

Senhas com scrypt + sal; sessão em cookie `HttpOnly` + `SameSite=Lax` (30 dias; o token é guardado só como hash); desativar/redefinir senha derruba as sessões; o dono do levantamento vem da sessão (o cliente não consegue indicar outro); entradas validadas e limitadas; arquivos de outros usuários respondem 404; ao sair (logout) ou trocar de usuário, os dados locais do aparelho são apagados.

## Limitações conhecidas

- **Ler cliente do carimbo** usa a camada de texto do PDF. Plantas com fonte de traço (sem texto) não têm nada para ler — o app Android usa OCR nesses casos; aqui não há OCR.
- A biblioteca de logos fica **no aparelho** (cada aparelho adiciona os seus).
- Conflito de edição simultânea do mesmo marcador em dois aparelhos: vence a edição mais recente.
- Painel de administração exige internet.

## Estrutura

```
server/   Express + node:sqlite (usuários, sessões, sincronização, arquivos)
src/      PWA (Vite): viewer.js (zoom/pan), store.js (IndexedDB offline), sync.js, report.js (PDF), backup.js …
test/     node --test  (domínio portado do Kotlin + servidor/isolamento)
```
