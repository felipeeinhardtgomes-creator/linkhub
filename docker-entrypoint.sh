#!/bin/sh
set -e

echo "Aplicando migrations do Prisma..."
npx prisma migrate deploy --schema=./prisma/schema.prisma

echo "Iniciando servidor Next.js..."
exec node server.js
