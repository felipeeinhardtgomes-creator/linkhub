// Copia as fontes padrão do pdf.js (para PDFs com fontes não embutidas) para public/pdfjs.
import fs from 'node:fs';
const src = new URL('../node_modules/pdfjs-dist/standard_fonts', import.meta.url);
const dst = new URL('../public/pdfjs/standard_fonts', import.meta.url);
fs.mkdirSync(dst, { recursive: true }); fs.cpSync(src, dst, { recursive: true });
