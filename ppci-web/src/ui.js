// ---------- utilidades ----------
export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else { try { if (k in el && k !== 'list') el[k] = v; else el.setAttribute(k, v === true ? '' : v); } catch { el.setAttribute(k, v === true ? '' : v); } } // ex.: textarea só tem getter de `type`
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(kid));
  return el;
}
export function toast(msg, ms = 2800) { const t = h('div', { class: 'toast' }, msg); document.body.append(t); setTimeout(() => t.remove(), ms); }
export const fmtDate = (t) => new Date(t).toLocaleDateString('pt-BR');
export const slug = (s) => (s || 'obra').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9]+/g, '_').slice(0, 30);
export function download(blob, name) {
  const url = URL.createObjectURL(blob); const a = h('a', { href: url, download: name }); document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
/** Celular/tablet: abre a folha de compartilhamento (WhatsApp, e-mail, Drive…); computador: baixa o arquivo. */
export async function deliver(blob, name) {
  const file = new File([blob], name, { type: blob.type });
  if (matchMedia('(pointer:coarse)').matches && navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: name }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  download(blob, name);
}
export const busy = (label) => { const el = h('div', { class: 'scrim', style: { alignItems: 'center' } }, h('div', { class: 'card', style: { margin: 16 } }, label)); document.body.append(el); return { set: (t) => (el.firstChild.textContent = t), done: () => el.remove() }; };

