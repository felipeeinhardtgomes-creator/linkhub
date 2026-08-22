# LinkHub — ambiente Docker

Stack: **Next.js 14 + Prisma + PostgreSQL**, rodando 100% em containers.

## Estrutura

```
.
├── Dockerfile            # imagem de produção (multi-stage, standalone)
├── Dockerfile.dev         # imagem de desenvolvimento (hot reload)
├── docker-compose.yml     # ambiente de DEV (app + db + adminer)
├── docker-compose.prod.yml # ambiente de PRODUÇÃO (app + db)
├── docker-entrypoint.sh   # roda "prisma migrate deploy" antes do server
├── prisma/
│   ├── schema.prisma      # modelo de dados (categorias, apps/links, tema, layout, etc.)
│   └── seed.ts            # dados de exemplo opcionais (podem ser apagados pelo admin)
└── src/
    ├── app/               # App Router do Next.js
    └── lib/prisma.ts      # cliente Prisma singleton
```

## Como rodar em desenvolvimento

```bash
cp .env.example .env
docker compose up --build
```

- App: http://localhost:3000
- Adminer (gerenciador do banco): http://localhost:8080
  - Sistema: PostgreSQL · Servidor: `db` · Usuário/senha: os do `.env`
- O container já roda `prisma migrate deploy` automaticamente ao subir.

Popular com dados de exemplo (opcional):

```bash
docker compose exec app npm run db:seed
```

Criar a primeira migration (depois de editar o schema.prisma):

```bash
docker compose exec app npx prisma migrate dev --name init
```

## Como rodar em produção

```bash
cp .env.example .env   # ajuste usuário/senha reais
docker compose -f docker-compose.prod.yml up --build -d
```

A imagem de produção usa o build `standalone` do Next.js (imagem final enxuta)
e aplica as migrations automaticamente no start via `docker-entrypoint.sh`.

## TrueNAS SCALE (Custom App via YAML)

O TrueNAS SCALE (24.10 "Electric Eel" em diante) permite instalar apps via
**Apps -> Discover Apps -> ⋮ -> Install via YAML**, colando um Docker Compose.
Duas diferenças importantes em relação a rodar localmente:

1. **O TrueNAS só faz `pull`, nunca `build`.** A YAML colada não tem acesso
   ao seu código-fonte/Dockerfile, então o serviço `linkhub-app` precisa
   apontar para uma **imagem já publicada** num registry (GHCR, Docker Hub,
   ou um registry privado). Veja a seção abaixo sobre como gerar essa imagem.
2. **Persistência em caminhos de host, não em volumes nomeados.** Use
   `docker-compose.truenas.yml`, que já monta tudo sob `/data/linkhub/...`.

### Passo a passo

1. Garanta que `/data` (ou o caminho real do seu dataset, geralmente
   `/mnt/<pool>/<dataset>`) existe no host. Pelo shell do TrueNAS:
   ```bash
   mkdir -p /data/linkhub/postgres /data/linkhub/uploads
   ```
   Se preferir usar um dataset ZFS de verdade (recomendado, para permitir
   snapshots), crie-o pela UI (Datasets -> Add Dataset) e ajuste os
   caminhos no YAML para o mountpoint real (ex: `/mnt/tank/apps/linkhub/...`).
2. Publique a imagem do LinkHub num registry (ver seção seguinte) e anote
   a tag, ex: `ghcr.io/seu-usuario/linkhub:latest`.
3. No TrueNAS: **Apps -> Discover Apps -> ⋮ -> Install via YAML**.
4. Dê um nome ao app (ex: `linkhub`) no campo de nome.
5. Cole o conteúdo de `docker-compose.truenas.yml` no editor de YAML.
6. Edite a senha (`CHANGE_ME_STRONG_PASSWORD`, aparece 2x — precisa ser
   **idêntica** nos dois lugares) e a tag da imagem (`image:` do
   `linkhub-app`).
7. Salve. O TrueNAS roda `docker compose up -d` e aplica as migrations
   automaticamente (via `docker-entrypoint.sh`, já embutido na imagem).
8. Acesse `http://<ip-do-truenas>:3050` (ou a porta que você escolher).

### Build e publicação da imagem

A imagem **não está publicada publicamente por mim** — eu não tenho como
publicar em registries a partir deste ambiente. Duas formas de gerar a sua:

**Opção A — automática via GitHub Actions (recomendada)**

Já incluí `.github/workflows/docker-publish.yml` no projeto. Basta:
```bash
git init && git add . && git commit -m "LinkHub"
git remote add origin https://github.com/SEU_USUARIO/linkhub.git
git push -u origin main
```
O workflow builda multi-arquitetura (amd64 + arm64) e publica automaticamente em
`ghcr.io/SEU_USUARIO/linkhub:latest` a cada push na branch `main`. Depois do
primeiro push, vá em GitHub -> seu perfil -> **Packages** -> `linkhub` ->
**Package settings** -> mude a visibilidade para **Public** (ou configure um
`imagePullSecret`/login no TrueNAS, se quiser manter privada).

**Opção B — build manual local e push**

Com Docker instalado na sua máquina:
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  -t ghcr.io/SEU_USUARIO/linkhub:latest \
  --push .
```
(faça `docker login ghcr.io` antes, com um Personal Access Token com escopo
`write:packages`).

**Opção C — 100% offline, sem registry**

Se preferir não publicar em lugar nenhum:
```bash
docker build -t linkhub:latest .
docker save -o linkhub.tar linkhub:latest
scp linkhub.tar root@<ip-do-truenas>:/tmp/
ssh root@<ip-do-truenas> "docker load -i /tmp/linkhub.tar"
```
Depois, no YAML do Custom App, use `image: linkhub:latest` (sem registry) —
como a imagem já está carregada localmente no host, o TrueNAS não tenta
puxar de lugar nenhum.

## Princípio do projeto

Nada de conteúdo fixo no código: apps, links, categorias, textos do cabeçalho,
tema, layout e página inicial são todos registros no PostgreSQL, editáveis
pela área de admin (a construir sobre este schema Prisma). O `prisma/seed.ts`
só existe para facilitar o primeiro boot — pode ser apagado inteiramente
pela interface de admin sem tocar em código.
