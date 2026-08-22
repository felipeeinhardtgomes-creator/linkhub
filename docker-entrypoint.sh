#!/bin/sh
set -e

echo "Aplicando migrations do Prisma..."

./node_modules/.bin/prisma migrate deploy

echo "Iniciando servidor Next.js..."

exec node server.js
