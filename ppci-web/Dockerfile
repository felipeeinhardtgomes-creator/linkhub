# ==========================================================
# PPCI Web — imagem de produção (amd64 e arm64)
# Build do front (Vite/PWA) + servidor Node 22 com SQLite embutido (node:sqlite).
# Sem dependências nativas: não precisa de compilador na imagem.
# ==========================================================
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm test && npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production PORT=3000 DATA_DIR=/data NODE_NO_WARNINGS=1
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY server ./server
COPY --from=build /app/dist ./dist
# /data guarda o banco (ppci.db) e os arquivos (PDFs/fotos). Monte um volume/dataset aqui.
RUN mkdir -p /data && chown -R node:node /data /app
VOLUME /data
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "server/index.js"]
