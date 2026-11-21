import test from 'node:test';
import assert from 'node:assert/strict';
import { group, csvItems, csvGrouped } from '../src/summary.js';
import { parseTitleBlock } from '../src/titleblock.js';
import { STATUS } from '../src/domain.js';

const m = (category, spec, status, note = '') => ({ category, spec, status, note, page: 0, x: .5, y: .5 });
const sample = [
  m('EXTINGUISHER', 'ABC 6kg', 'MISSING'),
  m('EXTINGUISHER', ' abc 6KG ', 'IRREGULAR'), // mesma especificação: maiúsculas e espaços não separam grupos
  m('EXTINGUISHER', 'ABC 6kg', 'OK'),
  m('SIGN', 'Saída S1', 'MISSING', 'pé-direito 6m; precisa "andaime"'),
];

test('agrupa por especificação ignorando maiúsculas e espaços', () => {
  const g = group(sample);
  assert.equal(g.length, 2);
  const ext = g.find((x) => x.category === 'EXTINGUISHER');
  assert.deepEqual([ext.missing, ext.irregular, ext.ok], [1, 1, 1]);
  assert.deepEqual(ext.numbers.MISSING, [1]); assert.deepEqual(ext.numbers.IRREGULAR, [2]);
});
test('grupos ordenados por categoria', () => assert.deepEqual(group(sample).map((x) => x.category), ['SIGN', 'EXTINGUISHER']));
test('CSV de itens tem BOM, cabeçalho e escape', () => {
  const csv = csvItems(sample); assert.ok(csv.startsWith('﻿'));
  const lines = csv.slice(1).split('\r\n');
  assert.equal(lines[0], 'Item;Categoria;Especificação;Status;Quantidade;Observação');
  assert.equal(lines.length, 5);
  assert.ok(lines[4].endsWith('"pé-direito 6m; precisa ""andaime"""'));
});
test('CSV agrupado lista os números dos pinos', () => {
  const lines = csvGrouped(sample).slice(1).split('\r\n');
  assert.ok(lines.some((l) => l.includes('NÃO TEM') && l.includes('#1')));
  assert.ok(lines.every((l) => !l.includes(';0;')));
});
test('só MISSING e IRREGULAR entram no orçamento', () => {
  assert.ok(STATUS.MISSING.budgeted && STATUS.IRREGULAR.budgeted && !STATUS.OK.budgeted);
});

// Textos baseados em carimbos de plantas A0 reais de AutoCAD
test('cliente em carimbo real, com dois-pontos e rótulos em volta', () => {
  const tb = parseTitleBlock(`PROGRAMA : PLANTA BAIXA
    RESP. TECNICO:DINARTE AVILA DE OLIVEIRA JR
    REG. CREA N°: RS 120025
    CLIENTE : TATIANE LEIRIA FERNANDES
    ENDEREÇO : RUA DOS PLATANOS 300
    DATA OUT 25 ESCALA 1:100 FOLHA 01`);
  assert.equal(tb.client, 'TATIANE LEIRIA FERNANDES');
});
test('OCR sem dois-pontos', () => {
  assert.equal(parseTitleBlock('CLIENTE TATIANE LEIRIA FERNANDES ENDEREÇO RUA DOS PLATANOS 300 ESCALA 1:100').client, 'TATIANE LEIRIA FERNANDES');
});
test('PROPRIETÁRIO também vale como cliente', () => {
  assert.equal(parseTitleBlock('PROPRIETÁRIO: JOAO SILVA OBRA GALPAO ESCALA 1:50').client, 'JOAO SILVA');
});
test('texto de legenda não gera campos falsos', () => {
  assert.ok(parseTitleBlock('LEGENDA PPCI EXTINTOR PÓ QUIMICO SECO ( PQS ) - BC PLACA SAIDA 240/120').isEmpty);
});
test('rótulo vazio seguido de outro rótulo é ignorado', () => {
  assert.equal(parseTitleBlock('CLIENTE : ENDEREÇO: RUA A 10 ESCALA 1:100').client, '');
});
