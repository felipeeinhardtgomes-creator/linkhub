import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createApp } from '../server/index.js';

process.env.PPCI_ADMIN_USER = 'admin';
process.env.PPCI_ADMIN_PASSWORD = 'senha-admin-123';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ppci-'));
const { app, db } = createApp({ dataDir: dir });
const server = app.listen(0);
const base = `http://127.0.0.1:${server.address().port}`;
test.after(() => { server.close(); db.close(); fs.rmSync(dir, { recursive: true, force: true }); });

async function call(method, url, { body, cookie, raw, type } = {}) {
  const headers = {};
  if (cookie) headers.cookie = cookie;
  if (body !== undefined && !raw) headers['content-type'] = 'application/json';
  if (raw) headers['content-type'] = type || 'application/pdf';
  const r = await fetch(base + url, { method, headers, body: raw ? raw : body !== undefined ? JSON.stringify(body) : undefined });
  const text = await r.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: r.status, data, cookie: (r.headers.get('set-cookie') || '').split(';')[0] };
}
const login = async (u, p) => (await call('POST', '/api/login', { body: { username: u, password: p } }));

const survey = (id, extra = {}) => ({ id, company: 'ACME', client: 'Cliente ' + id, site: '', technician: '', createdAt: 1, pdfBlob: 'pdf-' + id + '-0000', pageCount: 1, updatedAt: 10, deleted: 0, ...extra });
const marker = (id, surveyId, extra = {}) => ({ id, surveyId, page: 0, x: .5, y: .5, category: 'EXTINGUISHER', spec: 'ABC', status: 'MISSING', note: '', photoBlob: null, createdAt: 1, updatedAt: 10, deleted: 0, ...extra });

test('login exige credenciais corretas e API protegida', async () => {
  assert.equal((await call('GET', '/api/me')).status, 401);
  assert.equal((await login('admin', 'errada')).status, 401);
  const ok = await login('admin', 'senha-admin-123');
  assert.equal(ok.status, 200); assert.equal(ok.data.role, 'admin');
});

test('admin cria usuários; técnico não acessa o painel', async () => {
  const admin = (await login('admin', 'senha-admin-123')).cookie;
  assert.equal((await call('POST', '/api/admin/users', { cookie: admin, body: { username: 'ana', name: 'Ana', password: '12345678', role: 'tecnico' } })).status, 201);
  assert.equal((await call('POST', '/api/admin/users', { cookie: admin, body: { username: 'bruno', name: 'Bruno', password: '12345678' } })).status, 201);
  assert.equal((await call('POST', '/api/admin/users', { cookie: admin, body: { username: 'ana', password: '12345678' } })).status, 409);
  assert.equal((await call('POST', '/api/admin/users', { cookie: admin, body: { username: 'Ab', password: '12345678' } })).status, 400);
  assert.equal((await call('POST', '/api/admin/users', { cookie: admin, body: { username: 'curto', password: '123' } })).status, 400);
  const ana = (await login('ana', '12345678')).cookie;
  assert.equal((await call('GET', '/api/admin/users', { cookie: ana })).status, 403);
  assert.equal((await call('POST', '/api/admin/users', { cookie: ana, body: { username: 'x1x', password: '12345678' } })).status, 403);
});

test('cada usuário vê somente os levantamentos que ele fez', async () => {
  const ana = (await login('ana', '12345678')).cookie;
  const bruno = (await login('bruno', '12345678')).cookie;
  const admin = (await login('admin', 'senha-admin-123')).cookie;

  await call('POST', '/api/sync', { cookie: ana, body: { since: 0, surveys: [survey('s-ana-0001')], markers: [marker('m-ana-0001', 's-ana-0001')] } });
  await call('POST', '/api/sync', { cookie: bruno, body: { since: 0, surveys: [survey('s-bru-0001')], markers: [marker('m-bru-0001', 's-bru-0001')] } });

  const a = (await call('POST', '/api/sync', { cookie: ana, body: { since: 0 } })).data;
  const b = (await call('POST', '/api/sync', { cookie: bruno, body: { since: 0 } })).data;
  const ad = (await call('POST', '/api/sync', { cookie: admin, body: { since: 0 } })).data;
  assert.deepEqual(a.surveys.map((s) => s.id), ['s-ana-0001']);
  assert.deepEqual(a.markers.map((m) => m.id), ['m-ana-0001']);
  assert.deepEqual(b.surveys.map((s) => s.id), ['s-bru-0001']);
  assert.equal(a.surveys[0].createdBy, 'ana');
  assert.equal(ad.surveys.length, 0); // admin também só vê o que ele mesmo fez
});

test('não dá para invadir dados de outro usuário (sobrescrever, injetar marcador, ler arquivo)', async () => {
  const ana = (await login('ana', '12345678')).cookie;
  const bruno = (await login('bruno', '12345678')).cookie;

  // Bruno tenta sobrescrever o levantamento da Ana e injetar marcador nele
  await call('POST', '/api/sync', { cookie: bruno, body: { since: 0, surveys: [survey('s-ana-0001', { client: 'HACK', updatedAt: 999 })], markers: [marker('m-hack-0001', 's-ana-0001')] } });
  const a = (await call('POST', '/api/sync', { cookie: ana, body: { since: 0 } })).data;
  assert.equal(a.surveys[0].client, 'Cliente s-ana-0001');
  assert.deepEqual(a.markers.map((m) => m.id), ['m-ana-0001']);

  // Arquivos: Ana envia o PDF; Bruno não lê nem sobrescreve
  const pdf = Buffer.from('%PDF-1.4 teste');
  assert.equal((await call('PUT', '/api/blobs/pdf-s-ana-0001-0000', { cookie: ana, raw: pdf })).status, 200);
  assert.equal((await call('GET', '/api/blobs/pdf-s-ana-0001-0000', { cookie: ana })).status, 200);
  assert.equal((await call('GET', '/api/blobs/pdf-s-ana-0001-0000', { cookie: bruno })).status, 404);
  assert.equal((await call('PUT', '/api/blobs/pdf-s-ana-0001-0000', { cookie: bruno, raw: Buffer.from('x') })).status, 403);
  assert.equal((await call('GET', '/api/blobs/pdf-s-ana-0001-0000')).status, 401);
});

test('dono vem da sessão, não do cliente; edição mais nova vence; exclusão sincroniza', async () => {
  const bruno = (await login('bruno', '12345678')).cookie;
  await call('POST', '/api/sync', { cookie: bruno, body: { since: 0, surveys: [survey('s-bru-0002', { createdBy: 'ana' })] } });
  let r = (await call('POST', '/api/sync', { cookie: bruno, body: { since: 0 } })).data;
  assert.equal(r.surveys.find((s) => s.id === 's-bru-0002').createdBy, 'bruno');

  await call('POST', '/api/sync', { cookie: bruno, body: { since: 0, markers: [marker('m-bru-0001', 's-bru-0001', { status: 'OK', updatedAt: 20 })] } });
  await call('POST', '/api/sync', { cookie: bruno, body: { since: 0, markers: [marker('m-bru-0001', 's-bru-0001', { status: 'MISSING', updatedAt: 15 })] } }); // mais antiga: ignorada
  r = (await call('POST', '/api/sync', { cookie: bruno, body: { since: 0 } })).data;
  assert.equal(r.markers.find((m) => m.id === 'm-bru-0001').status, 'OK');

  const since = r.seq;
  await call('POST', '/api/sync', { cookie: bruno, body: { since, markers: [marker('m-bru-0001', 's-bru-0001', { deleted: 1, updatedAt: 30 })] } });
  const d = (await call('POST', '/api/sync', { cookie: bruno, body: { since } })).data;
  assert.equal(d.markers.length, 1); assert.equal(d.markers[0].deleted, 1); assert.equal(d.surveys.length, 0);
});

test('admin: desativar derruba sessão, protege o último admin, bloqueia exclusão com dados', async () => {
  const admin = (await login('admin', 'senha-admin-123')).cookie;
  const users = (await call('GET', '/api/admin/users', { cookie: admin })).data;
  const ana = users.find((u) => u.username === 'ana'); const adm = users.find((u) => u.username === 'admin');
  const anaCookie = (await login('ana', '12345678')).cookie;

  assert.equal((await call('PATCH', `/api/admin/users/${ana.id}`, { cookie: admin, body: { active: false } })).status, 200);
  assert.equal((await call('GET', '/api/me', { cookie: anaCookie })).status, 401);
  assert.equal((await login('ana', '12345678')).status, 401);
  assert.equal((await call('PATCH', `/api/admin/users/${ana.id}`, { cookie: admin, body: { active: true, password: 'nova-senha-1' } })).status, 200);
  assert.equal((await login('ana', 'nova-senha-1')).status, 200);

  assert.equal((await call('PATCH', `/api/admin/users/${adm.id}`, { cookie: admin, body: { role: 'tecnico' } })).status, 400);
  assert.equal((await call('DELETE', `/api/admin/users/${adm.id}`, { cookie: admin })).status, 400);
  assert.equal((await call('DELETE', `/api/admin/users/${ana.id}`, { cookie: admin })).status, 409); // tem levantamento
});
