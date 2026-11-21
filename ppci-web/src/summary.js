import { CATEGORIES, CATEGORY_KEYS, STATUS, STATUS_KEYS } from './domain.js';

/** Agrupa por categoria + especificação (ignora maiúsculas e espaços). A numeração dos pinos é global (posição na lista). */
export function group(markers) {
  const map = new Map();
  markers.forEach((m, i) => {
    const spec = (m.spec || '').trim();
    const key = m.category + '|' + spec.toLowerCase();
    let g = map.get(key);
    if (!g) map.set(key, (g = { category: m.category, spec, count: {}, numbers: {} }));
    g.count[m.status] = (g.count[m.status] || 0) + 1;
    (g.numbers[m.status] ||= []).push(i + 1);
  });
  const rows = [...map.values()].map((g) => ({ ...g, missing: g.count.MISSING || 0, irregular: g.count.IRREGULAR || 0, ok: g.count.OK || 0 }));
  return rows.sort((a, b) => CATEGORY_KEYS.indexOf(a.category) - CATEGORY_KEYS.indexOf(b.category) || a.spec.toLowerCase().localeCompare(b.spec.toLowerCase()));
}

// CSV com ';' e BOM UTF-8: abre direto no Excel em português, com acentos corretos.
const esc = (v) => { const s = v == null ? '' : String(v); return /[;"\n\r]/.test(s) ? '"' + s.replaceAll('"', '""') + '"' : s; };
const build = (rows) => '﻿' + rows.map((r) => r.map(esc).join(';')).join('\r\n');

export const csvItems = (markers) => build([
  ['Item', 'Categoria', 'Especificação', 'Status', 'Quantidade', 'Observação'],
  ...markers.map((m, i) => [i + 1, CATEGORIES[m.category].label, m.spec, STATUS[m.status].label, 1, m.note]),
]);

/** Pronto para cruzar com a tabela de preços: uma linha por categoria + especificação + status. */
export function csvGrouped(markers) {
  const rows = [['Categoria', 'Especificação', 'Status', 'Serviço a orçar', 'Quantidade', 'Itens (pinos)']];
  for (const g of group(markers)) for (const s of STATUS_KEYS) {
    const n = g.count[s] || 0;
    if (n > 0) rows.push([CATEGORIES[g.category].label, g.spec, STATUS[s].label, STATUS[s].service, n, (g.numbers[s] || []).map((x) => '#' + x).join(' ')]);
  }
  return build(rows);
}
