#!/bin/sh
set -e

echo "Aplicando schema do Prisma..."
# O projeto ainda não possui a pasta prisma/migrations.
# db push cria/atualiza as tabelas no PostgreSQL de forma simples.
prisma db push --schema=./prisma/schema.prisma

echo "Iniciando servidor Next.js..."
exec node server.js
