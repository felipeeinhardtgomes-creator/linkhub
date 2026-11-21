import { h, toast } from './ui.js';
import * as logos from './logos.js';

/**
 * Janela de exportação editável (relatório e proposta): logo da empresa (biblioteca no aparelho), título e nome da empresa.
 * `onGo({ logo, title, company, detail })` recebe o logo já como data URL. As escolhas ficam lembradas por `storageKey`.
 */
export async function openExportDialog({ heading, storageKey, defaultTitle, company, showDetail = false, goLabel = 'Gerar PDF', onGo }) {
  const saved = JSON.parse(localStorage.getItem(storageKey) || '{}');
  const o = { title: saved.title || defaultTitle, company: company || saved.company || '', detail: saved.detail !== false, logoId: saved.logoId || null };
  const logoBox = h('div', { class: 'chips' });
  const up = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', class: 'hidden', onchange: async () => {
    const fl = up.files[0]; up.value = ''; if (!fl) return;
    try { o.logoId = await logos.addLogo(fl); paintLogos(); } catch { toast('Não foi possível ler essa imagem.'); }
  } });
  async function paintLogos() {
    const list = await logos.listLogos(); if (o.logoId && !list.some((l) => l.id === o.logoId)) o.logoId = null; logoBox.replaceChildren();
    logoBox.append(h('button', { class: 'chipb' + (!o.logoId ? ' on' : ''), onclick: () => { o.logoId = null; paintLogos(); } }, 'Sem logo'));
    for (const l of list) {
      const blob = await logos.logoBlob(l.id); if (!blob) continue;
      const img = h('img', { src: URL.createObjectURL(blob), style: { height: 32, maxWidth: 90, objectFit: 'contain', verticalAlign: 'middle' } });
      logoBox.append(h('span', { class: 'chipb', style: { display: 'inline-flex', alignItems: 'center', gap: 6, background: '#fff', color: 'inherit', outline: o.logoId === l.id ? '3px solid var(--accent)' : 'none' } },
        h('span', { onclick: () => { o.logoId = l.id; paintLogos(); }, style: { display: 'inline-flex', alignItems: 'center', gap: 6 } }, img, h('small', {}, l.name)),
        h('button', { class: 'linkbtn', style: { color: 'var(--red)', padding: 2 }, title: 'Remover logo', onclick: async () => { await logos.removeLogo(l.id); if (o.logoId === l.id) o.logoId = null; paintLogos(); } }, '✕')));
    }
    logoBox.append(h('button', { class: 'chipb sug', onclick: () => up.click() }, '+ Adicionar logo'));
  }
  const titleIn = h('input', { type: 'text', value: o.title, oninput: (e) => (o.title = e.target.value) });
  const compIn = h('input', { type: 'text', value: o.company, oninput: (e) => (o.company = e.target.value) });
  const det = h('input', { type: 'checkbox', checked: o.detail, onchange: (e) => (o.detail = e.target.checked), style: { width: 20, height: 20, marginRight: 8 } });
  const go = h('button', { class: 'btn primary block', style: { marginTop: 14 }, onclick: async () => {
    localStorage.setItem(storageKey, JSON.stringify({ title: o.title, company: o.company, detail: o.detail, logoId: o.logoId })); dlg.remove();
    await onGo({ logo: await logos.logoDataUrl(o.logoId), title: o.title.trim() || defaultTitle, company: o.company.trim(), detail: o.detail });
  } }, goLabel);
  const dlg = h('div', { class: 'scrim', onclick: (e) => { if (e.target === dlg) dlg.remove(); } }, h('div', { class: 'sheet' }, h('h3', {}, heading),
    h('label', {}, 'Logo da empresa'), logoBox, up, h('label', {}, 'Título'), titleIn, h('label', {}, 'Nome da empresa (canto direito)'), compIn,
    showDetail && h('label', { style: { display: 'flex', alignItems: 'center', fontSize: 14, color: 'inherit', fontWeight: 400, marginTop: 14 } }, det, 'Incluir detalhe ampliado de cada marcação (planta + foto)'),
    h('p', { class: 'muted' }, 'O logo fica salvo neste aparelho para os próximos documentos.'), go));
  document.body.append(dlg); paintLogos();
}
