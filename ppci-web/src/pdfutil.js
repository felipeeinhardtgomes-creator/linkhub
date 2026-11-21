import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';
import { jsPDF } from 'jspdf';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

export async function loadPdf(blob) {
  const data = new Uint8Array(await blob.arrayBuffer());
  return pdfjs.getDocument({ data, standardFontDataUrl: '/pdfjs/standard_fonts/', isEvalSupported: false }).promise;
}

/** Texto da camada vetorial da prancha (carimbo). Plantas com fonte de traço podem não ter texto: aí devolve vazio. */
export async function pageText(doc, pageNo = 1) {
  const page = await doc.getPage(pageNo);
  const tc = await page.getTextContent();
  // pdf.js já emite os espaços como itens próprios; juntar com '' evita partir palavras em fontes com kerning ("RE"+"SP.")
  return tc.items.map((i) => i.str + (i.hasEOL ? '\n' : '')).join('');
}

/** Renderiza a prancha inteira num canvas com lado maior ≤ maxSide (para relatório). */
export async function renderPageToCanvas(doc, pageIndex, maxSide = 2400) {
  const page = await doc.getPage(pageIndex + 1);
  const v1 = page.getViewport({ scale: 1 });
  const scale = maxSide / Math.max(v1.width, v1.height);
  const viewport = page.getViewport({ scale });
  const c = document.createElement('canvas');
  c.width = Math.round(viewport.width); c.height = Math.round(viewport.height);
  const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  await page.render({ canvasContext: ctx, viewport }).promise;
  return c;
}

/**
 * Recorte de alta resolução da prancha ao redor de (cx, cy) normalizados. `frac` = largura do recorte como fração da LARGURA
 * da página; `aspect` = altura/largura do recorte. O recorte é renderizado direto do vetor (nítido mesmo em prancha A0)
 * e se mantém dentro da página. Devolve o canvas e onde ficou o ponto marcado dentro dele (0..1).
 */
export async function renderRegion(doc, pageIndex, cx, cy, frac = 0.1, aspect = 0.75, outPx = 900) {
  const page = await doc.getPage(pageIndex + 1);
  const v1 = page.getViewport({ scale: 1 });
  const rw = Math.min(v1.width, frac * v1.width), rh = Math.min(v1.height, rw * aspect);
  const left = Math.min(Math.max(cx * v1.width - rw / 2, 0), v1.width - rw);
  const top = Math.min(Math.max(cy * v1.height - rh / 2, 0), v1.height - rh);
  const scale = outPx / rw;
  const c = document.createElement('canvas'); c.width = Math.round(rw * scale); c.height = Math.round(rh * scale);
  const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  await page.render({ canvasContext: ctx, viewport: page.getViewport({ scale }), transform: [1, 0, 0, 1, -left * scale, -top * scale] }).promise;
  return { canvas: c, mark: { x: (cx * v1.width - left) / rw, y: (cy * v1.height - top) / rh } };
}

const canvasBlob = (c, q) => new Promise((res) => c.toBlob(res, 'image/jpeg', q));

/** Foto da câmera → JPEG com lado maior ≤ maxSide, já na orientação certa (EXIF). ~4 MB viram ~300 KB. */
export async function shrinkPhoto(file, maxSide = 1600, quality = 0.85) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const k = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height); bmp.close?.();
  return canvasBlob(c, quality);
}

/** PNG/JPG → PDF de 1 página (mesmo comportamento do app Android). */
export async function imageToPdf(file) {
  const jpeg = await shrinkPhoto(file, 6000, 0.9);
  const bmp = await createImageBitmap(jpeg);
  const w = bmp.width, h = bmp.height; bmp.close?.();
  const dataUrl = await new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(jpeg); });
  const pdf = new jsPDF({ unit: 'px', format: [w, h], orientation: w > h ? 'l' : 'p', hotfixes: ['px_scaling'] });
  pdf.addImage(dataUrl, 'JPEG', 0, 0, w, h);
  return pdf.output('blob');
}

export async function toPlanPdf(file) {
  if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) return file.type === 'application/pdf' ? file : new Blob([file], { type: 'application/pdf' });
  return imageToPdf(file);
}
