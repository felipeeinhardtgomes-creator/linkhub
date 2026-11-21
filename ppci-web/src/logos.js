import * as store from './store.js';

// Biblioteca de logos do aparelho (vários clientes/empresas). Ficam só no aparelho: são apagados no logout.
const KEY = 'logos';
export const listLogos = async () => (await store.getMeta(KEY)) || [];

/** Normaliza o arquivo: lado maior ≤ 600 px, PNG (mantém fundo transparente). */
async function normalize(file) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const k = Math.min(1, 600 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(bmp.width * k)); c.height = Math.max(1, Math.round(bmp.height * k));
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height); bmp.close?.();
  return new Promise((res) => c.toBlob(res, 'image/png'));
}

export async function addLogo(file) {
  const blob = await normalize(file);
  const id = 'logo_' + store.uid();
  await store.putBlob(id, blob, 1); // uploaded=1: não sobe para o servidor
  const list = await listLogos();
  list.push({ id, name: (file.name || 'logo').replace(/\.[^.]+$/, '').slice(0, 40) });
  await store.setMeta(KEY, list);
  return id;
}
export async function removeLogo(id) {
  await store.setMeta(KEY, (await listLogos()).filter((l) => l.id !== id));
}
const toDataUrl = (b) => new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(b); });
export async function logoDataUrl(id) { const b = id && (await store.localBlob(id)); return b ? toDataUrl(b) : null; }
export const logoBlob = (id) => store.localBlob(id);
