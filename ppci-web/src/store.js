// Banco local (IndexedDB): o app funciona 100% offline e sincroniza quando há rede.
// Stores: surveys, markers (com flag `dirty` = ainda não enviado), blobs (PDF/fotos; `uploaded`), meta.
const DB_NAME = 'ppci';
let dbp;
export const events = new EventTarget();
const emit = (name) => events.dispatchEvent(new Event(name));

function open() {
  return (dbp ||= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const d = req.result;
      d.createObjectStore('surveys', { keyPath: 'id' });
      d.createObjectStore('markers', { keyPath: 'id' }).createIndex('surveyId', 'surveyId');
      d.createObjectStore('blobs', { keyPath: 'id' });
      d.createObjectStore('meta', { keyPath: 'k' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  }));
}
const wrap = (r) => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
async function run(stores, mode, fn) {
  const d = await open(); const t = d.transaction(stores, mode);
  const done = new Promise((res, rej) => { t.oncomplete = res; t.onerror = () => rej(t.error); t.onabort = () => rej(t.error); });
  const out = await fn(...[].concat(stores).map((s) => t.objectStore(s)));
  await done; return out;
}

export const uid = () => crypto.randomUUID().replaceAll('-', '').slice(0, 24) + Math.random().toString(36).slice(2, 8);

// ---- meta ----
export const getMeta = (k) => run('meta', 'readonly', (s) => wrap(s.get(k))).then((r) => r?.v);
export const setMeta = (k, v) => run('meta', 'readwrite', (s) => wrap(s.put({ k, v })));

// ---- levantamentos ----
export const allSurveys = () => run('surveys', 'readonly', (s) => wrap(s.getAll())).then((l) => l.filter((x) => !x.deleted).sort((a, b) => b.createdAt - a.createdAt));
export const getSurvey = (id) => run('surveys', 'readonly', (s) => wrap(s.get(id)));
export async function saveSurvey(s) {
  await run('surveys', 'readwrite', (st) => wrap(st.put({ ...s, updatedAt: Date.now(), dirty: 1 })));
  emit('change'); emit('dirty');
}
export async function deleteSurvey(id) {
  const ms = await markersOf(id);
  const now = Date.now();
  await run(['surveys', 'markers'], 'readwrite', async (ss, mm) => {
    const s = await wrap(ss.get(id)); if (s) ss.put({ ...s, deleted: 1, updatedAt: now, dirty: 1 });
    for (const m of ms) mm.put({ ...m, deleted: 1, updatedAt: now, dirty: 1 });
  });
  emit('change'); emit('dirty');
}

// ---- marcadores ----
export const markersOf = (surveyId) => run('markers', 'readonly', (s) => wrap(s.index('surveyId').getAll(surveyId)))
  .then((l) => l.filter((m) => !m.deleted).sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id)));
export async function saveMarker(m) {
  await run('markers', 'readwrite', (s) => wrap(s.put({ ...m, updatedAt: Date.now(), dirty: 1, deleted: 0 })));
  emit('change'); emit('dirty');
}
export async function deleteMarker(m) {
  await run('markers', 'readwrite', (s) => wrap(s.put({ ...m, deleted: 1, updatedAt: Date.now(), dirty: 1 })));
  emit('change'); emit('dirty');
}
/** Especificações já usadas pelo técnico nesta categoria (mais recentes primeiro): o catálogo vem do uso. */
export async function recentSpecs(category) {
  const all = await run('markers', 'readonly', (s) => wrap(s.getAll()));
  const seen = new Map();
  for (const m of all.filter((x) => !x.deleted && x.category === category && x.spec.trim()).sort((a, b) => b.updatedAt - a.updatedAt)) {
    const k = m.spec.trim().toLowerCase(); if (!seen.has(k)) seen.set(k, m.spec.trim());
  }
  return [...seen.values()].slice(0, 12);
}

// ---- arquivos ----
export const putBlob = (id, blob, uploaded = 0) => run('blobs', 'readwrite', (s) => wrap(s.put({ id, blob, uploaded })));
export const localBlob = (id) => run('blobs', 'readonly', (s) => wrap(s.get(id))).then((r) => r?.blob);
export const pendingBlobs = () => run('blobs', 'readonly', (s) => wrap(s.getAll())).then((l) => l.filter((b) => !b.uploaded));
export const markUploaded = (id) => run('blobs', 'readwrite', async (s) => { const b = await wrap(s.get(id)); if (b) s.put({ ...b, uploaded: 1 }); });

// ---- sincronização (aplicação das linhas vindas do servidor) ----
export const dirtyRows = () => run(['surveys', 'markers'], 'readonly', async (ss, mm) => ({
  surveys: (await wrap(ss.getAll())).filter((x) => x.dirty), markers: (await wrap(mm.getAll())).filter((x) => x.dirty),
}));
/** Marca como enviado só o que não mudou durante o envio. */
export const settle = (sent) => run(['surveys', 'markers'], 'readwrite', async (ss, mm) => {
  for (const [store, rows] of [[ss, sent.surveys], [mm, sent.markers]]) for (const r of rows) {
    const cur = await wrap(store.get(r.id)); if (cur && cur.updatedAt === r.updatedAt) store.put({ ...cur, dirty: 0 });
  }
});
export const applyRemote = (incoming) => run(['surveys', 'markers'], 'readwrite', async (ss, mm) => {
  for (const [store, rows] of [[ss, incoming.surveys], [mm, incoming.markers]]) for (const r of rows) {
    const cur = await wrap(store.get(r.id));
    if (cur?.dirty) continue; // edição local ainda não enviada: não atropelar
    store.put({ ...r, dirty: 0 });
  }
}).then(() => emit('change'));

/** Troca de usuário ou logout: apaga tudo que é local, para não vazar dados entre contas no mesmo aparelho. */
export async function wipe() {
  await run(['surveys', 'markers', 'blobs', 'meta'], 'readwrite', (a, b, c, d) => Promise.all([a, b, c, d].map((s) => wrap(s.clear()))));
  emit('change');
}
