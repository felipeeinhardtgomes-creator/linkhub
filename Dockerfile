# ==========================================================
# LinkHub - Dockerfile de produção (amd64)
# ==========================================================

# ---- deps ----
FROM node:20-alpine AS deps
WORKDIR /app

# Prisma 5 precisa das bibliotecas OpenSSL disponíveis no Alpine.
RUN apk add --no-cache openssl

COPY package.json package-lock.json* ./
RUN npm install

# ---- builder ----
FROM node:20-alpine AS builder
WORKDIR /app

RUN apk add --no-cache openssl

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN npx prisma generate
RUN npm run build

# ---- runner ----
FROM node:20-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000

RUN apk add --no-cache openssl     && addgroup --system --gid 1001 nodejs     && adduser --system --uid 1001 nextjs

# Prisma CLI usada pelo entrypoint para aplicar o schema do banco.
RUN npm install -g prisma@5.22.0

COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder /app/prisma ./prisma
COPY --from=builder /app/node_modules/.prisma ./node_modules/.prisma
COPY --chown=nextjs:nodejs docker-entrypoint.sh ./docker-entrypoint.sh

RUN chmod +x ./docker-entrypoint.sh

USER nextjs
EXPOSE 3000

ENTRYPOINT ["./docker-entrypoint.sh"]
