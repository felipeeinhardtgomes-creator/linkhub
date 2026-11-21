import { jsPDF } from 'jspdf';
import autoTable from 'jspdf-autotable';
import { CATEGORIES, STATUS, counts } from './domain.js';
import { group } from './summary.js';
import { renderPageToCanvas, renderRegion } from './pdfutil.js';
import { getBlob } from './sync.js';

const INK = [17, 24, 39], MUTED = [91, 103, 118], LINE = [201, 207, 214], ACCENT = [217, 80, 11], HEAD = [243, 244, 246];
const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const blobToDataUrl = (b) => new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(b); });

/**
 * Marca VAZADA: anel + 4 traços de mira, com o centro totalmente transparente, para o símbolo da planta continuar visível
 * por dentro. O número fica numa etiqueta ao lado, ligada por uma linha curta (não cobre o item).
 */
function drawMark(ctx, x, y, r, n, status, { tag = true, dir = 1 } = {}) {
  const col = STATUS[status].color, lw = Math.max(2, r * 0.22);
  const ring = (w, style) => { ctx.lineWidth = w; ctx.strokeStyle = style; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.stroke(); };
  const ticks = (w, style) => { ctx.lineWidth = w; ctx.strokeStyle = style; ctx.beginPath();
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { ctx.moveTo(x + dx * r * 1.15, y + dy * r * 1.15); ctx.lineTo(x + dx * r * 1.75, y + dy * r * 1.75); } ctx.stroke(); };
  ring(lw + 3, '#ffffff'); ticks(lw + 3, '#ffffff'); // halo branco: a marca aparece também sobre desenho escuro
  ring(lw, col); ticks(lw, col);
  if (!tag) return;
  const fs = r * 1.1; ctx.font = `bold ${fs}px sans-serif`; ctx.textBaseline = 'middle'; ctx.textAlign = 'center';
  const label = String(n), tw = ctx.measureText(label).width + fs * 0.9, th = fs * 1.4;
  const tx = x + dir * (r * 2.3 + tw / 2), ty = y - r * 2.1;
  ctx.lineWidth = lw * 0.6; ctx.strokeStyle = col; ctx.beginPath(); ctx.moveTo(x + dir * r * 0.75, y - r * 0.75); ctx.lineTo(tx - dir * tw / 2, ty + th / 2); ctx.stroke();
  ctx.fillStyle = col; ctx.beginPath(); ctx.roundRect(tx - tw / 2, ty - th / 2, tw, th, th / 3); ctx.fill();
  ctx.lineWidth = 2; ctx.strokeStyle = '#fff'; ctx.stroke();
  ctx.fillStyle = STATUS[status].ink; ctx.fillText(label, tx, ty + fs * 0.05);
}

function drawPins(canvas, markers, page) {
  const ctx = canvas.getContext('2d'); const r = Math.max(9, canvas.width / 150);
  markers.forEach((m, i) => { if (m.page === page) drawMark(ctx, m.x * canvas.width, m.y * canvas.height, r, i + 1, m.status, { dir: m.x > 0.85 ? -1 : 1 }); }); // numeração global
}

/** Relatório técnico A4: cabeçalho + resumo quantitativo, planta anotada, detalhamento item a item, anexo fotográfico. */
export async function buildReport(survey, markers, pdfDoc, onProgress = () => {}, opts = {}) {
  // Opções editáveis na janela de exportação: logo (data URL PNG/JPEG), título, empresa, detalhe visual por marcação.
  const o = { logo: null, title: 'Relatório de Levantamento de PPCI', company: survey.company || '', detail: true, author: survey.createdByName || '', ...opts };
  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  const W = 595, H = 842, M = 36, cw = W - 2 * M;
  const c = counts(markers);
  let y = M;

  let tx = M;
  if (o.logo) { // logo à esquerda, proporcional, até 120 x 40 pt
    const pr = doc.getImageProperties(o.logo); const k = Math.min(120 / pr.width, 40 / pr.height);
    doc.addImage(o.logo, pr.fileType, M, y, pr.width * k, pr.height * k); tx = M + pr.width * k + 12; y += Math.max(0, pr.height * k - 22);
  }
  doc.setFont('helvetica', 'bold').setFontSize(o.logo ? 14 : 17).setTextColor(...INK);
  const tLines = doc.splitTextToSize(o.title, W - M - tx - 170).slice(0, 3); // título longo quebra em até 3 linhas sem invadir o subtítulo
  doc.text(tLines, tx, y + 14, { lineHeightFactor: 1.15 });
  doc.setFontSize(10).text(o.company, W - M, y + 12, { align: 'right', maxWidth: 160 });
  y += 8 + tLines.length * (o.logo ? 16 : 19);
  doc.setFont('helvetica', 'normal').setFontSize(7.5).setTextColor(...MUTED).text('Plano de Prevenção e Proteção Contra Incêndio — PPCI', tx, y + 6);
  doc.text('Visita em ' + new Date(survey.createdAt).toLocaleDateString('pt-BR'), W - M, y + 6, { align: 'right' });
  y += 14; doc.setDrawColor(...ACCENT).setLineWidth(2.5).line(M, y, W - M, y); y += 12;
  for (const [k, v] of [['Cliente', survey.client], ['Levantamento realizado por', o.author]]) {
    doc.setFontSize(7.5).setTextColor(...MUTED).text(k.toUpperCase(), M, y + 6); y += 10;
    const lines = doc.setFontSize(9).setTextColor(...INK).splitTextToSize(v || '—', cw); doc.text(lines, M, y + 7); y += lines.length * 11 + 6;
  }
  // KPIs
  const kw = (cw - 18) / 4;
  [['Itens a orçar', c.toBudget], ['Não tem', c.missing], ['Irregular', c.irregular], ['Existentes', c.existing]].forEach(([label, n], i) => {
    const x = M + i * (kw + 6); doc.setDrawColor(...LINE).setLineWidth(0.7).rect(x, y, kw, 38);
    doc.setFont('helvetica', 'bold').setFontSize(18).setTextColor(...INK).text(String(n), x + 6, y + 22);
    doc.setFont('helvetica', 'normal').setFontSize(7.5).setTextColor(...MUTED).text(label.toUpperCase(), x + 6, y + 33);
  });
  y += 48;

  const section = (t) => { if (y > H - 90) { doc.addPage(); y = M; } y += 6; doc.setFont('helvetica', 'bold').setFontSize(10.5).setTextColor(...INK).text(t.toUpperCase(), M, y + 10); y += 14; doc.setDrawColor(...INK).setLineWidth(1).line(M, y, W - M, y); y += 6; };
  const table = (head, body, opts = {}) => {
    autoTable(doc, { startY: y, head: [head], body, margin: { left: M, right: M }, theme: 'plain', styles: { fontSize: 9, textColor: INK, cellPadding: 4, lineColor: LINE, lineWidth: { bottom: 0.7 } },
      headStyles: { fillColor: HEAD, textColor: MUTED, fontSize: 7.5, fontStyle: 'bold' }, columnStyles: opts.columnStyles, didParseCell: opts.didParseCell });
    y = doc.lastAutoTable.finalY + 8;
  };
  const right = { 2: { halign: 'right' }, 3: { halign: 'right' }, 4: { halign: 'right' } };

  const groups = group(markers);
  section('1. Resumo quantitativo para orçamento');
  const budget = groups.filter((g) => g.missing + g.irregular > 0);
  table(['Categoria', 'Especificação', 'Não tem', 'Irregular', 'Total'], [
    ...budget.map((g) => [CATEGORIES[g.category].label, g.spec || 'Não especificado', g.missing, g.irregular, g.missing + g.irregular]),
    ['TOTAL A ORÇAR', '', c.missing, c.irregular, c.toBudget]], {
    columnStyles: right, didParseCell: (d) => { if (d.section === 'body' && d.row.index === budget.length) { d.cell.styles.fontStyle = 'bold'; d.cell.styles.fillColor = HEAD; } } });
  section('2. Já existente no local (registro técnico)');
  table(['Categoria', 'Especificação', 'Qtd.'], groups.filter((g) => g.ok > 0).map((g) => [CATEGORIES[g.category].label, g.spec || 'Não especificado', g.ok]), { columnStyles: { 2: { halign: 'right' } } });

  // Planta anotada: uma página por prancha com marcadores
  const pages = [...new Set(markers.map((m) => m.page))].sort((a, b) => a - b);
  const planPages = pages.length ? pages : [0];
  for (const p of planPages) {
    onProgress(`Renderizando prancha ${p + 1}…`);
    const canvas = await renderPageToCanvas(pdfDoc, p, 3600); drawPins(canvas, markers, p);
    doc.addPage(); y = M; section(planPages.length > 1 || survey.pageCount > 1 ? `3. Planta anotada — prancha ${p + 1}` : '3. Planta anotada');
    const k = Math.min(cw / canvas.width, (H - y - M - 30) / canvas.height); const pw = canvas.width * k, ph = canvas.height * k;
    const px = M + (cw - pw) / 2;
    doc.addImage(canvas.toDataURL('image/jpeg', 0.9), 'JPEG', px, y, pw, ph); doc.setDrawColor(...LINE).setLineWidth(0.7).rect(px, y, pw, ph);
    canvas.width = 0; // libera memória (iOS)
  }

  doc.addPage(); y = M; section('4. Detalhamento item a item');
  table(['Nº', 'Categoria', 'Especificação', 'Status', 'Observações de campo'],
    markers.map((m, i) => ['#' + (i + 1), CATEGORIES[m.category].label, m.spec || '—', STATUS[m.status].label, (survey.pageCount > 1 ? `[Prancha ${m.page + 1}] ` : '') + (m.note || '')]),
    { columnStyles: { 0: { cellWidth: 28 }, 1: { cellWidth: 115 }, 2: { cellWidth: 105 }, 3: { cellWidth: 62 } },
      didParseCell: (d) => { if (d.section === 'body' && d.column.index === 3) { d.cell.styles.textColor = rgb(STATUS[markers[d.row.index].status].color); d.cell.styles.fontStyle = 'bold'; } } });

  // Detalhe visual de CADA marcação: recorte ampliado da planta com a marca vazada no ponto exato + foto de campo ao lado.
  if (markers.length && o.detail) {
    doc.addPage(); y = M; section('5. Detalhe visual de cada marcação');
    doc.setFont('helvetica', 'normal').setFontSize(7.5).setTextColor(...MUTED);
    doc.text('Recorte ampliado da planta no ponto marcado (a marca é vazada: o item desenhado aparece por dentro do anel). Número = posição na planta anotada.', M, y + 8, { maxWidth: cw }); y += 20;
    const cardH = 196, imgW = (cw - 10) / 2, imgH = 150;
    for (let i = 0; i < markers.length; i++) {
      const m = markers[i];
      onProgress(`Detalhando marcação ${i + 1} de ${markers.length}…`);
      if (y + cardH > H - M - 10) { doc.addPage(); y = M; }
      const st = STATUS[m.status];
      doc.setFillColor(...rgb(st.color)).roundedRect(M, y, 22 + String(i + 1).length * 6, 16, 4, 4, 'F');
      doc.setFont('helvetica', 'bold').setFontSize(9).setTextColor(...rgb(st.ink)).text('#' + (i + 1), M + 6, y + 11.5);
      doc.setTextColor(...INK).text(`${CATEGORIES[m.category].label}${m.spec ? ' · ' + m.spec : ''}`, M + 34 + String(i + 1).length * 6, y + 11.5, { maxWidth: cw - 150 });
      doc.setTextColor(...rgb(st.color)).text(st.label + (survey.pageCount > 1 ? `  ·  prancha ${m.page + 1}` : ''), W - M, y + 11.5, { align: 'right' });
      let ty = y + 22;
      const { canvas, mark } = await renderRegion(pdfDoc, m.page, m.x, m.y, 0.07, imgH / imgW, 1000);
      drawMark(canvas.getContext('2d'), mark.x * canvas.width, mark.y * canvas.height, canvas.width / 24, i + 1, m.status, { tag: false });
      doc.addImage(canvas.toDataURL('image/jpeg', 0.9), 'JPEG', M, ty, imgW, imgH); doc.setDrawColor(...LINE).setLineWidth(0.7).rect(M, ty, imgW, imgH);
      doc.setFont('helvetica', 'normal').setFontSize(7).setTextColor(...MUTED).text('Planta (ampliada)', M + 3, ty + imgH + 8);
      canvas.width = 0;
      let photo = null;
      if (m.photoBlob) { const blob = await getBlob(m.photoBlob); if (blob) photo = await blobToDataUrl(blob); }
      if (photo) {
        const pr = doc.getImageProperties(photo); const k = Math.min(imgW / pr.width, imgH / pr.height); const pw = pr.width * k, ph = pr.height * k;
        doc.addImage(photo, 'JPEG', M + imgW + 10 + (imgW - pw) / 2, ty + (imgH - ph) / 2, pw, ph); doc.setDrawColor(...LINE).rect(M + imgW + 10, ty, imgW, imgH);
        doc.text('Foto de campo', M + imgW + 13, ty + imgH + 8);
      } else {
        doc.setDrawColor(...LINE).setLineDashPattern([3, 3], 0).rect(M + imgW + 10, ty, imgW, imgH).setLineDashPattern([], 0);
        doc.setFontSize(8).text('Sem foto', M + imgW + 10 + imgW / 2, ty + imgH / 2, { align: 'center' });
      }
      if (m.note) { doc.setFont('helvetica', 'normal').setFontSize(8).setTextColor(...INK).text(doc.splitTextToSize('Obs.: ' + m.note, cw).slice(0, 2), M, ty + imgH + 17); }
      y += cardH + (m.note ? 8 : 0);
    }
  }
  const n = doc.getNumberOfPages();
  for (let i = 1; i <= n; i++) { doc.setPage(i); doc.setFont('helvetica', 'normal').setFontSize(7.5).setTextColor(...MUTED).text(`${o.company || 'PPCI'}${o.author ? ' · ' + o.author : ''} · página ${i}`, M, H - 18); }
  return doc.output('blob');
}
