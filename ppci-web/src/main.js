import './styles.css';
import { registerSW } from 'virtual:pwa-register';
import { api, authEvents } from './api.js';
import * as store from './store.js';
import * as syncer from './sync.js';
import { CATEGORIES, CATEGORY_KEYS, STATUS, STATUS_KEYS, counts } from './domain.js';
import { csvItems, csvGrouped } from './summary.js';
import { parseTitleBlock } from './titleblock.js';
import { loadPdf, pageText, shrinkPhoto, toPlanPdf } from './pdfutil.js';
import { PlanViewer } from './viewer.js';
import { buildReport } from './report.js';
import { exportBackup, readBackup } from './backup.js';
import * as logos from './logos.js';
import { h, toast, fmtDate, slug, deliver, busy } from './ui.js';
import { openExportDialog } from './exportDialog.js';

registerSW({ immediate: true });

const app = document.getElementById('app');
let user = null;           // { id, username, name, role }
let cleanup = () => {};    // desmonta a tela atual

// ---------- sessão ----------
async function boot() {
  authEvents.addEventListener('expired', () => { if (user) { user = null; localStorage.removeItem('ppci.user'); route(); toast('Sessão expirada. Entre novamente.'); } });
  try { user = await api('GET', '/api/me'); }
  catch (e) {
    if (e.status === 401) user = null;
    else { try { user = JSON.parse(localStorage.getItem('ppci.user')); } catch { user = null; } } // sem rede: abre com o último usuário (dados locais)
  }
  if (user) await adopt(user);
  addEventListener('hashchange', route);
  syncer.syncEvents.addEventListener('status', paintStatus);
  route();
  if (user) syncer.start();
}
/** Se mudou o usuário neste aparelho, apaga os dados locais do anterior. */
async function adopt(u) {
  const prev = await store.getMeta('userId');
  if (prev !== undefined && prev !== u.id) await store.wipe();
  await store.setMeta('userId', u.id);
  localStorage.setItem('ppci.user', JSON.stringify(u));
}
async function logout() {
  try { await api('POST', '/api/logout'); } catch { /* offline: mesmo assim sai localmente */ }
  await store.wipe(); localStorage.removeItem('ppci.user'); user = null; location.hash = ''; route();
}

// ---------- roteamento ----------
function route() {
  cleanup(); cleanup = () => {}; app.replaceChildren();
  if (!user) return loginView();
  const m = location.hash.match(/^#\/s\/([\w-]+)/);
  if (m) return viewerView(m[1]);
  if (location.hash === '#/admin' && user.role === 'admin') return shell('admin', adminView);
  shell('home', homeView);
}

let statusChip;
function paintStatus() {
  if (!statusChip) return;
  const s = syncer.status.state;
  const map = { idle: ['ok', 'Sincronizado'], syncing: ['', 'Sincronizando…'], offline: ['off', 'Offline'], error: ['err', 'Erro ao sincronizar'] };
  statusChip.className = 'chip ' + map[s][0]; statusChip.textContent = map[s][1];
}
function shell(tab, body) {
  statusChip = h('span', { class: 'chip' }); paintStatus();
  const tabs = [['home', 'Levantamentos', '#/'], ...(user.role === 'admin' ? [['admin', 'Administração', '#/admin']] : [])];
  const top = h('div', { class: 'top' },
    h('div', { class: 'row' }, h('h1', {}, 'PPCI — Levantamento'), statusChip,
      h('button', { class: 'linkbtn', onclick: logout, title: 'Sair' }, `${user.name || user.username} · sair`)),
    h('div', { class: 'tabs' }, tabs.map(([k, label, href]) => h('button', { class: 'tab' + (k === tab ? ' on' : ''), onclick: () => (location.hash = href) }, label))));
  const main = h('main'); app.append(top, main); body(main);
}

// ---------- login ----------
function loginView() {
  const err = h('p', { class: 'err-text' });
  const u = h('input', { type: 'text', autocomplete: 'username', autocapitalize: 'none', autocorrect: 'off', required: true });
  const p = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const btn = h('button', { class: 'btn primary block', type: 'submit', style: { marginTop: 16 } }, 'Entrar');
  app.append(h('div', { class: 'login' },
    h('img', { class: 'logo', src: '/icons/icon.svg', alt: '' }), h('h1', {}, 'PPCI — Levantamento'), h('p', { class: 'muted' }, 'Entre com o usuário criado pelo administrador.'),
    h('form', {
      onsubmit: async (e) => {
        e.preventDefault(); btn.disabled = true; err.textContent = '';
        try { user = await api('POST', '/api/login', { username: u.value, password: p.value }); await adopt(user); route(); syncer.start(); }
        catch (x) { err.textContent = x.status ? x.message : 'Sem conexão com o servidor.'; } finally { btn.disabled = false; }
      },
    }, h('label', {}, 'Usuário'), u, h('label', {}, 'Senha'), p, btn, err)));
}

// ---------- início: novo levantamento + lista ----------
async function homeView(main) {
  const f = { company: (await store.getMeta('company')) || '', client: '' };
  let plan = null; const msg = h('p', { class: 'muted' });
  const inp = (k, label, multi) => { const el = h(multi ? 'textarea' : 'input', { type: 'text', value: f[k], oninput: (e) => (f[k] = e.target.value) }); f['_' + k] = el; return [h('label', {}, label), el]; };
  const startBtn = h('button', { class: 'btn primary block', disabled: true, style: { marginTop: 14 } }, 'Iniciar levantamento');
  const file = h('input', { type: 'file', accept: 'application/pdf,image/png,image/jpeg,.pdf', class: 'hidden' });
  const pickBtn = h('button', { class: 'btn block', onclick: () => file.click() }, 'Importar planta (PDF, PNG ou JPG)');
  file.onchange = async () => {
    const fl = file.files[0]; file.value = ''; if (!fl) return;
    msg.textContent = 'Lendo a planta…';
    try {
      plan = await toPlanPdf(fl); const doc = await loadPdf(plan); plan.pages = doc.numPages;
      pickBtn.textContent = `Planta pronta (${doc.numPages} prancha${doc.numPages > 1 ? 's' : ''}) — trocar`; startBtn.disabled = false; msg.textContent = '';
      try { // sugestão do cliente a partir do carimbo (camada de texto vetorial); só preenche se estiver vazio
        const tb = parseTitleBlock(await pageText(doc, 1));
        if (!f.client && tb.client) { f.client = tb.client; f._client.value = tb.client; msg.textContent = 'Cliente preenchido a partir do carimbo — confira.'; }
      } catch { /* sem texto no PDF: segue sem sugestão */ }
    } catch { plan = null; startBtn.disabled = true; msg.textContent = 'Não foi possível ler esse arquivo.'; }
  };
  startBtn.onclick = async () => {
    if (!plan) return;
    await store.setMeta('company', f.company);
    const id = store.uid(), pdfBlob = 'pdf_' + store.uid();
    await store.putBlob(pdfBlob, plan, 0);
    await store.saveSurvey({ id, company: f.company.trim(), client: f.client.trim(), createdAt: Date.now(), pdfBlob, pageCount: plan.pages, createdBy: user.username, deleted: 0 });
    location.hash = '#/s/' + id;
  };
  const backupIn = h('input', { type: 'file', accept: '.zip,application/zip', class: 'hidden', onchange: async () => {
    const fl = backupIn.files[0]; backupIn.value = ''; if (!fl) return; const b = busy('Importando backup…');
    try {
      const r = await readBackup(fl); const id = store.uid(), pdfBlob = 'pdf_' + store.uid();
      await store.putBlob(pdfBlob, r.planBlob, 0);
      await store.saveSurvey({ id, company: r.meta.company, client: r.meta.client, createdAt: r.meta.createdAt, pdfBlob, pageCount: r.pageCount, createdBy: user.username, deleted: 0 });
      const ids = {}; for (const [name, blob] of Object.entries(r.photos)) { ids[name] = 'photo_' + store.uid(); await store.putBlob(ids[name], blob, 0); }
      let t = Date.now(); for (const m of r.markers) await store.saveMarker({ id: store.uid(), surveyId: id, ...m, photoBlob: m.photo ? ids[m.photo] ?? null : null, createdAt: m.createdAt || t++ });
      location.hash = '#/s/' + id;
    } catch (e) { toast('Backup inválido: ' + e.message); } finally { b.done(); }
  } });

  main.append(
    h('div', { class: 'card' }, h('h2', {}, 'Novo levantamento'), pickBtn, file, msg,
      inp('company', 'Empresa'), inp('client', 'Cliente'), startBtn,
      h('p', {}, h('button', { class: 'linkbtn', style: { color: 'var(--accent)' }, onclick: () => backupIn.click() }, 'Importar backup (.zip)')), backupIn));

  const list = h('div'); main.append(h('h2', {}, 'Levantamentos anteriores'), list);
  async function paint() {
    const ss = await store.allSurveys(); list.replaceChildren();
    if (!ss.length) return list.append(h('p', { class: 'muted' }, 'Nenhum levantamento ainda. Importe uma planta acima para começar.'));
    for (const s of ss) {
      const c = counts(await store.markersOf(s.id)); const total = c.missing + c.irregular + c.ok;
      let confirming = false; const actions = h('div', { class: 'row' });
      const draw = () => { actions.replaceChildren(...[].concat(confirming
        ? [h('button', { class: 'btn danger', onclick: async (e) => { e.stopPropagation(); await store.deleteSurvey(s.id); } }, 'Excluir?'), h('button', { class: 'btn', onclick: (e) => { e.stopPropagation(); confirming = false; draw(); } }, 'Cancelar')]
        : h('button', { class: 'icon', title: 'Excluir', onclick: (e) => { e.stopPropagation(); confirming = true; draw(); } }, '🗑'))); }; draw();
      list.append(h('div', { class: 'card item', onclick: () => (location.hash = '#/s/' + s.id) },
        h('div', { class: 'grow' }, h('div', { class: 't' }, s.client || 'Levantamento sem título'), h('div', { class: 's' }, `${fmtDate(s.createdAt)} · ${c.toBudget} a orçar de ${total} marcados` + (s.pageCount > 1 ? ` · ${s.pageCount} pranchas` : ''))), actions));
    }
  }
  paint(); store.events.addEventListener('change', paint);
  cleanup = () => store.events.removeEventListener('change', paint);
}

// ---------- painel de administração ----------
async function adminView(main) {
  const list = h('div'); const err = h('p', { class: 'err-text' }); const okMsg = h('p', { class: 'ok-text' });
  const f = { username: h('input', { type: 'text', autocapitalize: 'none', autocomplete: 'off', placeholder: 'ex.: joao.silva' }), name: h('input', { type: 'text', placeholder: 'Nome completo' }),
    password: h('input', { type: 'password', autocomplete: 'new-password', placeholder: 'mínimo 8 caracteres' }),
    role: h('select', {}, h('option', { value: 'tecnico' }, 'Técnico (vê só os próprios levantamentos)'), h('option', { value: 'admin' }, 'Administrador (gerencia usuários)')) };
  const create = h('button', { class: 'btn primary block', style: { marginTop: 14 }, onclick: async () => {
    err.textContent = okMsg.textContent = '';
    try {
      const u = await api('POST', '/api/admin/users', { username: f.username.value.trim(), name: f.name.value, password: f.password.value, role: f.role.value });
      okMsg.textContent = `Usuário "${u.username}" criado. Passe a senha ao técnico por um canal seguro.`; f.username.value = f.name.value = f.password.value = ''; load();
    } catch (e) { err.textContent = e.status ? e.message : 'Sem conexão: a administração exige internet.'; }
  } }, 'Criar usuário');

  async function act(u, body, okText) { try { await api('PATCH', `/api/admin/users/${u.id}`, body); toast(okText); load(); } catch (e) { toast(e.message); } }
  const actions = (u) => {
    const self = u.id === user.id;
    return h('div', { class: 'row wrap' },
      h('button', { class: 'btn', onclick: () => { const pw = prompt(`Nova senha para ${u.username} (mínimo 8 caracteres):`); if (pw) act(u, { password: pw }, 'Senha redefinida e sessões encerradas'); } }, 'Redefinir senha'),
      !self && h('button', { class: 'btn', onclick: () => act(u, { active: !u.active }, u.active ? 'Usuário desativado' : 'Usuário ativado') }, u.active ? 'Desativar' : 'Ativar'),
      !self && h('button', { class: 'btn', onclick: () => act(u, { role: u.role === 'admin' ? 'tecnico' : 'admin' }, 'Perfil alterado') }, u.role === 'admin' ? 'Tornar técnico' : 'Tornar admin'),
      !self && h('button', { class: 'btn danger', onclick: async () => { if (!confirm(`Excluir o usuário ${u.username}?`)) return; try { await api('DELETE', `/api/admin/users/${u.id}`); load(); } catch (e) { toast(e.message); } } }, 'Excluir'));
  };
  async function load() {
    try {
      const us = await api('GET', '/api/admin/users'); list.replaceChildren();
      const badges = (u) => [h('span', { class: 'badge' + (u.role === 'admin' ? ' admin' : '') }, u.role === 'admin' ? 'Admin' : 'Técnico'), ' ', !u.active && h('span', { class: 'badge off' }, 'Desativado')];
      list.append(h('table', { class: 'users' }, h('thead', {}, h('tr', {}, ['Usuário', 'Perfil', 'Levantamentos', 'Criado em', ''].map((t) => h('th', {}, t)))),
        h('tbody', {}, us.map((u) => h('tr', {}, h('td', {}, h('b', {}, u.username), h('div', { class: 'muted' }, u.name)), h('td', {}, badges(u)), h('td', {}, String(u.surveys)), h('td', {}, fmtDate(u.createdAt)), h('td', {}, actions(u)))))),
        h('div', { class: 'users-cards' }, us.map((u) => h('div', { class: 'ucard' }, h('div', {}, h('b', {}, u.username), ' ', badges(u)), h('div', { class: 'muted' }, `${u.name || '—'} · ${u.surveys} levantamento(s) · ${fmtDate(u.createdAt)}`), h('div', { style: { marginTop: 8 } }, actions(u))))));
    } catch (e) { list.replaceChildren(h('p', { class: 'err-text' }, e.status ? e.message : 'A administração exige conexão com o servidor.')); }
  }
  main.append(
    h('div', { class: 'card' }, h('h2', {}, 'Novo usuário'), h('label', {}, 'Usuário (login)'), f.username, h('label', {}, 'Nome'), f.name, h('label', {}, 'Senha inicial'), f.password, h('label', {}, 'Perfil'), f.role, create, err, okMsg),
    h('div', { class: 'card' }, h('h2', {}, 'Usuários'), h('p', { class: 'muted' }, 'Cada usuário enxerga somente os levantamentos que ele mesmo criou. Desativar um usuário encerra as sessões dele e preserva os levantamentos.'), list));
  load();
}

// ---------- visualizador ----------
async function viewerView(id) {
  const survey = await store.getSurvey(id);
  if (!survey || survey.deleted) { location.hash = ''; return; }
  let markers = await store.markersOf(id), page = 0, editing = null, menuEl = null, viewer = null, pdfDoc = null;
  const ttl = h('b'), cnt = h('span'); const zl = h('div', { class: 'zoomlbl' }, '100%'); const loadingEl = h('div', { class: 'loading' }, 'Abrindo planta…');
  const pager = h('div', { class: 'pager' }); const host = h('div');
  const menuBtn = h('button', { class: 'icon', title: 'Exportar', onclick: toggleMenu }, '⤓');
  const stage = h('div', { class: 'vstage' }, host,
    h('div', { class: 'vctl v-ui' }, h('button', { onclick: () => viewer?.zoomBy(1.5), 'aria-label': 'Aproximar' }, '+'), h('button', { onclick: () => viewer?.zoomBy(1 / 1.5), 'aria-label': 'Afastar' }, '−'), h('button', { onclick: () => viewer?.fit(), 'aria-label': 'Ajustar' }, '⤢')), zl, pager, loadingEl);
  app.append(h('div', { class: 'vwrap' }, h('div', { class: 'vbar' }, h('button', { class: 'icon', onclick: () => (location.hash = ''), 'aria-label': 'Voltar' }, '←'), h('div', { class: 'ttl' }, ttl, cnt), menuBtn), stage));

  function paintHead() {
    const c = counts(markers); ttl.textContent = survey.client || 'Levantamento sem título'; cnt.textContent = `${c.toBudget} a orçar · ${c.existing} existentes`;
    pager.replaceChildren(); pager.classList.toggle('hidden', survey.pageCount <= 1);
    if (survey.pageCount > 1) pager.append(h('button', { disabled: page === 0, onclick: () => goPage(page - 1) }, '‹'), h('span', {}, `Prancha ${page + 1}/${survey.pageCount}`), h('button', { disabled: page >= survey.pageCount - 1, onclick: () => goPage(page + 1) }, '›'));
  }
  function paintPins() { viewer?.setMarkers(markers.map((m, i) => ({ ...m, n: i + 1 })).filter((m) => m.page === page), editing?.marker?.id ?? null); }
  async function goPage(p) { page = p; editing = null; closeSheet(); paintHead(); paintPins(); await viewer.setPage(p); }
  async function reload() { markers = await store.markersOf(id); paintHead(); paintPins(); }
  store.events.addEventListener('change', reload);

  // ---- folha de marcação ----
  let sheetEl = null; const closeSheet = () => { sheetEl?.remove(); sheetEl = null; viewer?.setDraft(null); };
  async function openSheet(marker, x, y) {
    closeSheet(); editing = { marker }; if (marker) paintPins(); else viewer.setDraft({ x, y });
    let category = marker?.category || (await store.getMeta('lastCategory')) || 'EXTINGUISHER', spec = marker?.spec || '', note = marker?.note || '', photo = marker?.photoBlob || null;
    const num = marker ? markers.indexOf(markers.find((m) => m.id === marker.id)) + 1 : markers.length + 1;
    const specIn = h('input', { type: 'text', value: spec, oninput: (e) => (spec = e.target.value), placeholder: 'ex.: PQS ABC 6 kg' });
    const noteIn = h('textarea', { oninput: (e) => (note = e.target.value) }, note);
    const catBox = h('div', { class: 'chips' }), sugBox = h('div', { class: 'chips' }), thumb = h('span');
    async function paintCat() {
      catBox.replaceChildren(...CATEGORY_KEYS.map((k) => h('button', { class: 'chipb' + (k === category ? ' on' : ''), onclick: () => { category = k; paintCat(); } }, CATEGORIES[k].short)));
      const sug = [...new Set([...(await store.recentSpecs(category)), ...CATEGORIES[category].subtypes])];
      sugBox.replaceChildren(...sug.map((s) => h('button', { class: 'chipb sug', onclick: () => { spec = s; specIn.value = s; } }, s)));
    }
    async function paintThumb() { thumb.replaceChildren(); const b = photo && (await syncer.getBlob(photo)); if (b) thumb.append(h('img', { class: 'thumb', src: URL.createObjectURL(b) })); }
    const cam = h('input', { type: 'file', accept: 'image/*', capture: 'environment', class: 'hidden', onchange: async () => {
      const fl = cam.files[0]; cam.value = ''; if (!fl) return;
      const pid = 'photo_' + store.uid(); await store.putBlob(pid, await shrinkPhoto(fl), 0); photo = pid; paintThumb();
    } });
    async function save(status) {
      await store.setMeta('lastCategory', category);
      await store.saveMarker({ id: marker?.id || store.uid(), surveyId: id, page: marker?.page ?? page, x: marker?.x ?? x, y: marker?.y ?? y, category, spec: spec.trim(), status, note: note.trim(), photoBlob: photo, createdAt: marker?.createdAt || Date.now() });
      editing = null; closeSheet();
    }
    const sbtn = (k, hgt) => { const s = STATUS[k]; return h('button', { class: 'btn status' + (marker?.status === k ? ' cur' : ''), style: { background: s.color, color: s.ink, height: hgt }, onclick: () => save(k) }, s.label); };
    sheetEl = h('div', { class: 'scrim', onclick: (e) => { if (e.target === sheetEl) { editing = null; closeSheet(); paintPins(); } } },
      h('div', { class: 'sheet' }, h('h3', {}, marker ? `Editar marcação #${num}` : `Nova marcação #${num}`), catBox, sugBox,
        h('label', {}, 'Especificação'), specIn, h('label', {}, 'Observação'), noteIn,
        h('div', { class: 'row', style: { margin: '10px 0' } }, h('button', { class: 'btn', onclick: () => cam.click() }, photo ? '📷 Trocar foto' : '📷 Tirar foto'), cam, thumb),
        h('div', { class: 'muted' }, marker ? 'Status (toque para salvar)' : 'Toque no status para SALVAR'), sbtn('MISSING', 64), sbtn('IRREGULAR', 52), sbtn('OK', 52),
        marker && h('div', { class: 'row', style: { justifyContent: 'space-between', marginTop: 12 } }, h('button', { class: 'btn danger', onclick: async () => { await store.deleteMarker(marker); editing = null; closeSheet(); } }, 'Excluir marcação'))));
    document.body.append(sheetEl); paintCat(); paintThumb();
  }

  // ---- exportação ----
  function closeMenu() { menuEl?.remove(); menuEl = null; }
  function toggleMenu() {
    if (menuEl) return closeMenu();
    const item = (label, fn) => h('button', { onclick: () => { closeMenu(); fn(); } }, label);
    menuEl = h('div', { class: 'menu' },
      item('Ler cliente do carimbo', readStamp), item('Relatório técnico em PDF…', exportPdfDialog),
      item('Exportar CSV (itens)', () => deliver(new Blob([csvItems(markers)], { type: 'text/csv' }), `itens_${slug(survey.client)}.csv`)),
      item('Exportar CSV (agrupado p/ orçamento)', () => deliver(new Blob([csvGrouped(markers)], { type: 'text/csv' }), `orcamento_${slug(survey.client)}.csv`)),
      item('Backup do levantamento (.zip)', async () => { const b = busy('Gerando backup…'); try { await deliver(await exportBackup(survey, markers), `backup_${slug(survey.client)}.ppci.zip`); } catch (e) { toast(e.message); } finally { b.done(); } }));
    document.body.append(menuEl);
  }
  async function readStamp() {
    const tb = parseTitleBlock(await pageText(pdfDoc, page + 1));
    alert(tb.isEmpty ? 'Não encontrei o cliente no texto desta prancha (plantas com fonte de traço não têm texto legível).' : `Cliente: ${tb.client}`);
  }

  /** Relatório técnico em PDF — editável: logo da empresa, título, nome da empresa e detalhe ampliado por marcação. */
  function exportPdfDialog() {
    openExportDialog({ heading: 'Exportar relatório em PDF', storageKey: 'ppci.export', defaultTitle: 'Relatório de Levantamento de PPCI', company: survey.company, showDetail: true,
      onGo: async (o) => {
        const b = busy('Gerando relatório…');
        try { const blob = await buildReport(survey, markers, pdfDoc, (t) => b.set(t), { ...o, author: survey.createdByName || user.name || user.username }); b.done(); await deliver(blob, `relatorio_${slug(survey.client)}.pdf`); }
        catch (e) { b.done(); console.error(e); toast('Falha ao gerar o PDF: ' + e.message); }
      } });
  }

  // ---- abertura ----
  paintHead();
  viewer = new PlanViewer(host, { onTapEmpty: (x, y) => openSheet(null, x, y), onTapMarker: (mid) => { const m = markers.find((k) => k.id === mid); if (m) openSheet(m); }, onView: (pct) => (zl.textContent = pct + '%') });
  try {
    const blob = await syncer.getBlob(survey.pdfBlob); if (!blob) throw new Error('PDF indisponível (abra uma vez com internet)');
    pdfDoc = await loadPdf(blob); await viewer.setDoc(pdfDoc); await viewer.setPage(0); paintPins(); loadingEl.remove();
  } catch (e) { loadingEl.textContent = 'Não foi possível abrir a planta: ' + e.message; }
  cleanup = () => { store.events.removeEventListener('change', reload); viewer?.destroy(); closeSheet(); closeMenu(); pdfDoc?.destroy?.(); };
}

boot();
