import { zipSync, unzipSync, strToU8, strFromU8 } from 'fflate';
import { CATEGORIES } from './domain.js';
import { getBlob } from './sync.js';
import { loadPdf } from './pdfutil.js';

const bytes = async (b) => new Uint8Array(await b.arrayBuffer());

/** Mesmo formato do app Android (survey.json + plan.pdf + photos/): dá para levar um levantamento do celular para o webapp e vice-versa. */
export async function exportBackup(survey, markers) {
  const files = {};
  const plan = await getBlob(survey.pdfBlob); if (!plan) throw new Error('PDF da planta indisponível offline');
  files['plan.pdf'] = await bytes(plan);
  const names = new Map();
  for (const m of markers) if (m.photoBlob && !names.has(m.photoBlob)) {
    const b = await getBlob(m.photoBlob); if (!b) continue;
    const name = m.photoBlob + '.jpg'; names.set(m.photoBlob, name); files['photos/' + name] = await bytes(b);
  }
  files['survey.json'] = strToU8(JSON.stringify({
    format: 1, company: survey.company, client: survey.client, site: '', technician: '', createdAt: survey.createdAt,
    markers: markers.map((m) => ({ page: m.page, x: m.x, y: m.y, category: m.category, spec: m.spec, status: m.status, note: m.note, photo: names.get(m.photoBlob) ?? null, createdAt: m.createdAt })),
  }));
  return new Blob([zipSync(files, { level: 1 })], { type: 'application/zip' });
}

/** Lê um backup e devolve os dados prontos para virar um NOVO levantamento (nunca sobrescreve). Só aceita nomes simples (anti zip-slip). */
export async function readBackup(file) {
  const z = unzipSync(await bytes(file));
  if (!z['survey.json']) throw new Error('Backup sem survey.json');
  if (!z['plan.pdf']) throw new Error('Backup sem plan.pdf');
  const meta = JSON.parse(strFromU8(z['survey.json']));
  const planBlob = new Blob([z['plan.pdf']], { type: 'application/pdf' });
  const doc = await loadPdf(planBlob); const pageCount = doc.numPages; doc.destroy?.();
  const photos = {};
  for (const [name, data] of Object.entries(z)) if (/^photos\/[^/\\]+$/.test(name)) photos[name.slice(7)] = new Blob([data], { type: 'image/jpeg' });
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, Number(v) || 0));
  return {
    meta: { company: String(meta.company ?? ''), client: String(meta.client ?? ''), createdAt: Number(meta.createdAt) || Date.now() },
    planBlob, pageCount, photos,
    markers: (meta.markers || []).map((m) => ({
      page: Math.round(clamp(m.page, 0, pageCount - 1)), x: clamp(m.x, 0, 1), y: clamp(m.y, 0, 1),
      category: m.category in CATEGORIES ? m.category : 'OTHER', spec: String(m.spec ?? ''),
      status: ['MISSING', 'IRREGULAR', 'OK'].includes(m.status) ? m.status : 'MISSING', note: String(m.note ?? ''),
      photo: typeof m.photo === 'string' ? m.photo : null, createdAt: Number(m.createdAt) || Date.now(),
    })),
  };
}
