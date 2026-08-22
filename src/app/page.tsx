import { prisma } from "@/lib/prisma";

export const dynamic = "force-dynamic";

export default async function HomePage() {
  const [header, categories] = await Promise.all([
    prisma.headerConfig.findUnique({ where: { id: 1 } }),
    prisma.category.findMany({
      where: { visible: true },
      orderBy: { order: "asc" },
      include: { items: { where: { visible: true, active: true }, orderBy: { order: "asc" } } },
    }),
  ]);

  return (
    <main style={{ padding: 32, fontFamily: "sans-serif" }}>
      <h1>{header?.showName === false ? "" : header?.hubName ?? "LinkHub"}</h1>
      <p>Nenhum conteúdo fixo — tudo abaixo vem do banco de dados.</p>

      {categories.length === 0 && <p>Nenhuma categoria cadastrada ainda. Use a área de admin.</p>}

      {categories.map((category) => (
        <section key={category.id} style={{ marginTop: 24 }}>
          <h2>
            {category.icon} {category.name}
          </h2>
          <ul>
            {category.items.map((item) => (
              <li key={item.id}>
                <strong>{item.name}</strong>
                {item.description ? ` — ${item.description}` : ""}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </main>
  );
}
