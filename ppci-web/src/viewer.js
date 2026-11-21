import { STATUS } from './domain.js';

const MAX_ZOOM = 40; // relativo ao "ajustar à tela"
const MIN_ZOOM = 0.6;

/**
 * Visualizador de prancha com zoom/pan por toque, mouse e roda.
 * Duas camadas: uma imagem base da página (baixa resolução, sempre pronta) e uma camada de DETALHE renderizada só na
 * região visível, na resolução real da tela (pdf.js), reposta ~120 ms depois de parar o gesto. Nunca existe um bitmap
 * da página inteira em alta resolução: uma planta A0 não estoura a memória do iPhone.
 * Coordenadas dos pinos são NORMALIZADAS (0..1, origem no topo-esquerdo), iguais às do app Android.
 */
export class PlanViewer {
  constructor(host, { onTapEmpty, onTapMarker, onView }) {
    this.host = host; this.cb = { onTapEmpty, onTapMarker, onView };
    host.classList.add('viewer');
    host.innerHTML = '<canvas class="v-base"></canvas><div class="v-detail"></div><div class="v-pins"></div>';
    [this.baseEl, this.detailHost, this.pinsEl] = host.children;
    this.detail = null; this.detailView = null;
    this.markers = []; this.draft = null; this.selectedId = null; this.pinEls = new Map();
    this.ptrs = new Map(); this.v = { s: 1, tx: 0, ty: 0 }; this.fitS = 1;
    this.token = 0; this.task = null; this.timer = null;
    this.ro = new ResizeObserver(() => this.onResize()); this.ro.observe(host);
    host.addEventListener('pointerdown', (e) => this.down(e));
    host.addEventListener('pointermove', (e) => this.move(e));
    host.addEventListener('pointerup', (e) => this.up(e));
    host.addEventListener('pointercancel', (e) => this.up(e, true));
    host.addEventListener('wheel', (e) => this.wheel(e), { passive: false });
  }

  destroy() { this.ro.disconnect(); this.task?.cancel?.(); clearTimeout(this.timer); }
  get zoomPct() { return Math.round((this.v.s / this.fitS) * 100); }
  get ready() { return !!this.page; }

  async setDoc(doc) { this.doc = doc; }
  async setPage(index) {
    const tok = ++this.token;
    const page = await this.doc.getPage(index + 1);
    if (tok !== this.token) return;
    this.page = page;
    const vp = page.getViewport({ scale: 1 });
    this.pw = vp.width; this.ph = vp.height; // pontos do PDF (já com rotação)
    this.clearDetail();
    await this.renderBase();
    this.fit();
  }

  async renderBase() {
    const k = 2048 / Math.max(this.pw, this.ph); this.baseK = k;
    const c = this.baseEl; c.width = Math.round(this.pw * k); c.height = Math.round(this.ph * k);
    c.style.width = this.pw + 'px'; c.style.height = this.ph + 'px'; // 1 unidade CSS = 1 ponto do PDF; escala via transform
    const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
    await this.page.render({ canvasContext: ctx, viewport: this.page.getViewport({ scale: k }) }).promise;
  }

  // ---- geometria ----
  size() { const r = this.host.getBoundingClientRect(); return { w: r.width, h: r.height }; }
  onResize() { if (!this.page) return; const was = this.v.s / this.fitS; this.computeFit(); this.v.s = this.fitS * was; this.clamp(); this.apply(true); }
  computeFit() { const { w, h } = this.size(); this.fitS = Math.min(w / this.pw, h / this.ph) * 0.98; }
  fit() {
    const { w, h } = this.size(); this.computeFit();
    this.v = { s: this.fitS, tx: (w - this.pw * this.fitS) / 2, ty: (h - this.ph * this.fitS) / 2 };
    this.apply(true);
  }
  clamp() {
    const { w, h } = this.size(); const v = this.v;
    v.s = Math.min(this.fitS * MAX_ZOOM, Math.max(this.fitS * MIN_ZOOM, v.s));
    const cw = this.pw * v.s, ch = this.ph * v.s, m = 0.2; // ao menos 20% da prancha sempre visível
    v.tx = cw <= w ? (w - cw) / 2 : Math.min(w * m, Math.max(w * (1 - m) - cw, v.tx));
    v.ty = ch <= h ? (h - ch) / 2 : Math.min(h * m, Math.max(h * (1 - m) - ch, v.ty));
  }
  zoomAt(factor, cx, cy) {
    const v = this.v; const px = (cx - v.tx) / v.s, py = (cy - v.ty) / v.s;
    v.s *= factor; this.clamp();
    v.tx = cx - px * v.s; v.ty = cy - py * v.s; this.clamp(); this.apply();
  }
  zoomBy(factor) { const { w, h } = this.size(); this.zoomAt(factor, w / 2, h / 2); }
  /** Normalizadas (0..1) → pixels de tela */
  toScreen(x, y) { return { x: this.v.tx + x * this.pw * this.v.s, y: this.v.ty + y * this.ph * this.v.s }; }
  toNorm(sx, sy) { return { x: (sx - this.v.tx) / (this.pw * this.v.s), y: (sy - this.v.ty) / (this.ph * this.v.s) }; }

  // ---- desenho ----
  apply(now = false) {
    const v = this.v;
    this.baseEl.style.transform = `translate(${v.tx}px,${v.ty}px) scale(${v.s})`;
    if (this.detail && this.detailView) {
      const k = v.s / this.detailView.s; // o detalhe antigo acompanha o gesto (borrado) até o novo ficar pronto
      this.detail.style.transform = `translate(${v.tx - k * this.detailView.tx}px,${v.ty - k * this.detailView.ty}px) scale(${k})`;
    }
    this.layoutPins(); this.cb.onView?.(this.zoomPct);
    clearTimeout(this.timer); this.timer = setTimeout(() => this.renderDetail(), now ? 30 : 120);
  }
  clearDetail() { this.task?.cancel?.(); this.detail?.remove(); this.detail = null; this.detailView = null; }

  async renderDetail() {
    if (!this.page) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 3); const { w, h } = this.size(); const v = { ...this.v };
    if (v.s * dpr <= this.baseK * 1.15) return this.clearDetail(); // a imagem base já é nítida o bastante
    this.task?.cancel?.();
    const c = document.createElement('canvas'); c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
    c.style.cssText = `position:absolute;left:0;top:0;width:${w}px;height:${h}px;transform-origin:0 0`;
    const ctx = c.getContext('2d');
    const task = this.page.render({ canvasContext: ctx, viewport: this.page.getViewport({ scale: v.s * dpr }), transform: [1, 0, 0, 1, v.tx * dpr, v.ty * dpr], background: 'rgba(0,0,0,0)' });
    this.task = task;
    try { await task.promise; } catch { return; } // cancelado por um gesto mais novo
    if (this.task !== task) return;
    this.detail?.remove(); this.detail = c; this.detailView = v; this.detailHost.appendChild(c); this.apply2();
  }
  apply2() { const v = this.v, d = this.detailView, k = v.s / d.s; this.detail.style.transform = `translate(${v.tx - k * d.tx}px,${v.ty - k * d.ty}px) scale(${k})`; }

  // ---- pinos ----
  setMarkers(list, selectedId = null) { this.markers = list; this.selectedId = selectedId; this.rebuildPins(); }
  setDraft(pt) { this.draft = pt; this.rebuildPins(); }
  rebuildPins() {
    this.pinsEl.replaceChildren(); this.pinEls.clear();
    const mk = (cls, text, color, ink) => { const d = document.createElement('div'); d.className = 'pin ' + cls; d.textContent = text; if (color) { d.style.background = color; d.style.color = ink; } this.pinsEl.appendChild(d); return d; };
    for (const m of this.markers) {
      const st = STATUS[m.status]; const el = mk(m.id === this.selectedId ? 'sel' : '', m.n, st.color, st.ink); this.pinEls.set(m.id, { el, m });
    }
    if (this.draft) this.pinEls.set('__draft', { el: mk('draft', '+'), m: this.draft });
    this.layoutPins();
  }
  layoutPins() { for (const { el, m } of this.pinEls.values()) { const p = this.toScreen(m.x, m.y); el.style.transform = `translate(${p.x}px,${p.y}px) translate(-50%,-50%)`; } }
  hitMarker(sx, sy) {
    let best = null, bd = 22 * 22; // raio de toque ~22 px, bom para dedo
    for (const { m } of this.pinEls.values()) {
      if (m.id === undefined) continue; const p = this.toScreen(m.x, m.y); const d = (p.x - sx) ** 2 + (p.y - sy) ** 2;
      if (d < bd) { bd = d; best = m; }
    }
    return best;
  }

  // ---- gestos ----
  local(e) { const r = this.host.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
  down(e) {
    if (e.target.closest('.v-ui')) return;
    this.host.setPointerCapture(e.pointerId);
    const p = this.local(e); this.ptrs.set(e.pointerId, { ...p, sx: p.x, sy: p.y, t: performance.now() });
    this.moved = this.ptrs.size > 1; this.last = this.ptrs.size === 2 ? this.pair() : null;
  }
  pair() { const [a, b] = [...this.ptrs.values()]; return { d: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 }; }
  move(e) {
    const st = this.ptrs.get(e.pointerId); if (!st) return;
    const p = this.local(e); const dx = p.x - st.x, dy = p.y - st.y; st.x = p.x; st.y = p.y;
    if (Math.hypot(p.x - st.sx, p.y - st.sy) > 8) this.moved = true;
    if (this.ptrs.size === 1 && this.moved) { this.v.tx += dx; this.v.ty += dy; this.clamp(); this.apply(); }
    else if (this.ptrs.size === 2) {
      const n = this.pair(), o = this.last; if (!o || !o.d) { this.last = n; return; }
      this.v.tx += n.cx - o.cx; this.v.ty += n.cy - o.cy;
      this.zoomAt(n.d / o.d, n.cx, n.cy); this.last = n;
    }
  }
  up(e, cancel = false) {
    const st = this.ptrs.get(e.pointerId); if (!st) return;
    this.ptrs.delete(e.pointerId);
    if (!cancel && !this.moved && this.ptrs.size === 0 && performance.now() - st.t < 600) this.tap(st.x, st.y);
    this.last = this.ptrs.size === 2 ? this.pair() : null;
    if (this.ptrs.size === 1) { const r = [...this.ptrs.values()][0]; r.sx = r.x; r.sy = r.y; this.moved = true; } // dedo restante só arrasta
  }
  tap(sx, sy) {
    const hit = this.hitMarker(sx, sy);
    if (hit) return this.cb.onTapMarker?.(hit.id);
    const n = this.toNorm(sx, sy);
    if (n.x < 0 || n.x > 1 || n.y < 0 || n.y > 1) return; // toque fora da prancha
    this.cb.onTapEmpty?.(n.x, n.y);
  }
  wheel(e) {
    e.preventDefault(); const p = this.local(e);
    this.zoomAt(Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015)), p.x, p.y);
  }
}
