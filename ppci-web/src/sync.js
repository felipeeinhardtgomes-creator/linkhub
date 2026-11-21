import { api, authEvents } from './api.js';
import * as store from './store.js';

export const syncEvents = new EventTarget();
let running = false, again = false, timer = null;
export const status = { state: 'idle', last: null }; // idle | syncing | offline | error
const set = (state) => { status.state = state; if (state === 'idle') status.last = Date.now(); syncEvents.dispatchEvent(new Event('status')); };

async function uploadBlobs() {
  for (const b of await store.pendingBlobs()) {
    const r = await fetch('/api/blobs/' + b.id, { method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': b.blob.type || 'application/octet-stream' }, body: b.blob });
    if (r.status === 401) { authEvents.dispatchEvent(new Event('expired')); throw new Error('auth'); }
    if (!r.ok) throw new Error('upload ' + r.status);
    await store.markUploaded(b.id);
  }
}

export async function sync() {
  if (!navigator.onLine) return set('offline');
  if (running) { again = true; return; }
  running = true; set('syncing');
  try {
    do {
      again = false;
      await uploadBlobs(); // arquivos antes dos registros que os referenciam
      const sent = await store.dirtyRows();
      const strip = ({ dirty, ...r }) => r; // eslint-disable-line no-unused-vars
      const since = (await store.getMeta('since')) || 0;
      const res = await api('POST', '/api/sync', { since, surveys: sent.surveys.map(strip), markers: sent.markers.map(strip) });
      await store.settle(sent);
      await store.applyRemote(res);
      await store.setMeta('since', res.seq);
    } while (again);
    set('idle');
    prefetchPlans(); // deixa os PDFs no aparelho para abrir sem sinal
  } catch (e) {
    set(navigator.onLine && e.message !== 'auth' ? 'error' : 'offline');
  } finally { running = false; }
}

/** Baixa o PDF (ou foto) do servidor se ainda não está no aparelho. */
export async function getBlob(id) {
  const local = await store.localBlob(id);
  if (local) return local;
  const r = await fetch('/api/blobs/' + id, { credentials: 'same-origin' });
  if (!r.ok) return null;
  const blob = await r.blob();
  await store.putBlob(id, blob, 1);
  return blob;
}
async function prefetchPlans() {
  try { for (const s of await store.allSurveys()) if (s.pdfBlob && !(await store.localBlob(s.pdfBlob))) await getBlob(s.pdfBlob); } catch { /* sem rede: tenta depois */ }
}

/** Dispara uma sincronização logo após edições (agrupando várias de uma vez). */
export function schedule(ms = 800) { clearTimeout(timer); timer = setTimeout(sync, ms); }
export function start() {
  store.events.addEventListener('dirty', () => schedule());
  addEventListener('online', () => sync());
  addEventListener('offline', () => set('offline'));
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') sync(); });
  setInterval(() => { if (document.visibilityState === 'visible') sync(); }, 60_000);
  sync();
}
