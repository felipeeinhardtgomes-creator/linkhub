// Lê o CLIENTE do carimbo da prancha (camada de texto vetorial do PDF). Lógica pura, testada com trechos reais de carimbos de AutoCAD.
// Tolera: dois-pontos ausentes (OCR costuma perder), maiúsculas/minúsculas e o valor colado no rótulo seguinte.
const STOP = 'CLIENTE|PROPRIET[ÁA]RIO|OBRA|ENDERE[ÇC]O|LOCAL|RESP\\.?\\s*T[ÉE]CNICO|RESPONS[ÁA]VEL|CREA|REG\\.|ENGENHEIR[OA]|ARQUITET[OA]|T[ÉE]CNICO|PRANCHA|ESCALA|DATA|PROJETO|PROGRAMA|FOLHA|FORMATO|DESENHO|REVIS[ÃA]O|ASSUNTO|DESCRI[ÇC][ÃA]O|EMISS[ÃA]O';
const startsWithStop = new RegExp(`^(?:${STOP}|RESP)`, 'i');
// JS não tem \\b com acentos; usamos bordas explícitas com lookarounds Unicode.
const B = '(?<![\\p{L}\\d])';
const E = '(?![\\p{L}\\d])';

function field(txt, ...labels) {
  for (const l of labels) {
    const m = new RegExp(`${B}(?:${l})${E}\\s*[:\\-]?\\s*(.{3,90}?)(?=\\s+(?:${STOP})${E}|$)`, 'iu').exec(txt);
    if (!m) continue;
    const v = m[1].trim().replace(/^[:\- ]+|[:\- ]+$/g, '');
    if (v.length >= 3 && !startsWithStop.test(v)) return v; // valor que já é o próximo rótulo = campo vazio
  }
  return '';
}

export function parseTitleBlock(raw) {
  const client = field(String(raw).replace(/\s+/g, ' '), 'CLIENTE', 'PROPRIET[ÁA]RIO');
  return { client, isEmpty: !client.trim() };
}
