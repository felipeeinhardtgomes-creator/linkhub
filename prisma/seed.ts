/**
 * Seed opcional de dados de demonstração.
 *
 * Importante: este script só POPULA o banco de dados; a aplicação
 * nunca lê esses valores do código-fonte. O administrador pode
 * apagar tudo pela área de admin sem tocar em nenhum arquivo.
 *
 * Rodar com: npm run db:seed
 */
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  await prisma.headerConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1, hubName: "LinkHub", showLogo: true, showName: true },
  });

  await prisma.searchConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1, showSearch: true, placeholderText: "Pesquisar serviços..." },
  });

  await prisma.toggleConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1 },
  });

  await prisma.themeConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1 },
  });

  await prisma.layoutConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1 },
  });

  await prisma.homePageConfig.upsert({
    where: { id: 1 },
    update: {},
    create: { id: 1, pageName: "LinkHub", title: "Meu Hub de Links" },
  });

  const armazenamento = await prisma.category.upsert({
    where: { id: "cat-armazenamento" },
    update: {},
    create: { id: "cat-armazenamento", name: "Armazenamento", icon: "📦", order: 1 },
  });

  await prisma.hubItem.upsert({
    where: { id: "item-nextcloud" },
    update: {},
    create: {
      id: "item-nextcloud",
      type: "APP",
      name: "Nextcloud",
      description: "Meu armazenamento pessoal",
      internalUrl: "http://192.168.1.30:11000",
      externalUrl: "https://cloud.exemplo.com",
      categoryId: armazenamento.id,
      order: 1,
      active: true,
    },
  });

  console.log("Seed concluído.");
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
