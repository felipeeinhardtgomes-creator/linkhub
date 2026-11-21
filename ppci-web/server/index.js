// Servidor do PPCI Web: Express + SQLite embutido do Node (node:sqlite, sem dependência nativa).
// Responsabilidades: usuários/sessões, painel de administração, sincronização dos levantamentos e armazenamento de PDFs/fotos.
//
// Regra de visibilidade: cada levantamento pertence a UM usuário (o que o criou) e só ele o enxerga.
// O dono é sempre definido AQUI, pela sessão — nunca pelo que o cliente envia.
import express from 'express';
import { DatabaseSync } from 'node:sqlite';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { hashPassword, verifyPassword, validUsername, validPassword } from './auth.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const BLOB_DIR = path.join(DATA_DIR, 'blobs');
const PORT = Number(process.env.PORT || 3000);
const SESSION_DAYS = 30;
const MAX_BLOB = 80 * 1024 * 1024;
fs.mkdirSync(BLOB_DIR, { recursive: true });

export function createApp({ dataDir = DATA_DIR } = {}) {
  fs.mkdirSync(path.join(dataDir, 'blobs'), { recursive: true });
  const blobDir = path.join(dataDir, 'blobs');
  const db = new DatabaseSync(path.join(dataDir, 'ppci.db'));
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL DEFAULT '',
      pass TEXT NOT NULL,
      role TEXT NOT NULL CHECK (role IN ('admin','tecnico')),
      active INTEGER NOT NULL DEFAULT 1,
      createdAt INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token TEXT PRIMARY KEY,           -- sha256 do token (o token em si só existe no cookie)
      userId INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS surveys (
      id TEXT PRIMARY KEY, ownerId INTEGER NOT NULL, seq INTEGER NOT NULL, updatedAt INTEGER NOT NULL, json TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS surveys_owner_seq ON surveys(ownerId, seq);
    CREATE TABLE IF NOT EXISTS markers (
      id TEXT PRIMARY KEY, surveyId TEXT NOT NULL, ownerId INTEGER NOT NULL, seq INTEGER NOT NULL, updatedAt INTEGER NOT NULL, json TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS markers_owner_seq ON markers(ownerId, seq);
    CREATE TABLE IF NOT EXISTS blobs (id TEXT PRIMARY KEY, ownerId INTEGER NOT NULL, type TEXT NOT NULL, size INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);
    INSERT OR IGNORE INTO meta(k, v) VALUES ('seq', 0);
  `);

  const q = (sql) => db.prepare(sql);
  const nextSeq = () => q('UPDATE meta SET v = v + 1 WHERE k = \'seq\' RETURNING v').get().v;
  const tx = (fn) => { db.exec('BEGIN IMMEDIATE'); try { const r = fn(); db.exec('COMMIT'); return r; } catch (e) { db.exec('ROLLBACK'); throw e; } };

  // ---- primeiro administrador ----
  if (q('SELECT COUNT(*) c FROM users').get().c === 0) {
    const username = (process.env.PPCI_ADMIN_USER || 'admin').toLowerCase();
    let password = process.env.PPCI_ADMIN_PASSWORD;
    const generated = !password;
    if (generated) password = crypto.randomBytes(9).toString('base64url');
    q('INSERT INTO users(username, name, pass, role, active, createdAt) VALUES (?,?,?,?,1,?)')
      .run(username, 'Administrador', hashPassword(password), 'admin', Date.now());
    console.log(`[ppci] Administrador criado: usuário "${username}"` + (generated ? ` senha "${password}" (gerada; troque no painel)` : ' (senha de PPCI_ADMIN_PASSWORD)'));
  }

  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', true); // atrás do Cloudflare Tunnel / proxy reverso
  app.use((req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'same-origin');
    res.setHeader('X-Frame-Options', 'DENY');
    next();
  });

  app.get('/api/health', (req, res) => res.json({ ok: true })); // healthcheck do Docker (sem autenticação, sem dados)

  // ---- sessões ----
  const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
  const cookies = (req) => Object.fromEntries((req.headers.cookie || '').split(';').map((c) => c.trim().split(/=(.*)/s).slice(0, 2)).filter((p) => p[0]));
  function currentUser(req) {
    const t = cookies(req).sid;
    if (!t) return null;
    return q(`SELECT u.id, u.username, u.name, u.role FROM sessions s JOIN users u ON u.id = s.userId
              WHERE s.token = ? AND s.expires > ? AND u.active = 1`).get(sha(t), Date.now()) || null;
  }
  const auth = (req, res, next) => { const u = currentUser(req); if (!u) return res.status(401).json({ error: 'Não autenticado' }); req.user = u; next(); };
  const adminOnly = (req, res, next) => (req.user.role === 'admin' ? next() : res.status(403).json({ error: 'Apenas administradores' }));
  const setCookie = (req, res, token, maxAge) =>
    res.setHeader('Set-Cookie', `sid=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${maxAge}` + (req.secure ? '; Secure' : ''));

  // limite simples contra tentativa de senha em força bruta (por IP + usuário)
  const attempts = new Map();
  function throttled(key) {
    const now = Date.now(); const a = (attempts.get(key) || []).filter((t) => now - t < 5 * 60_000);
    attempts.set(key, a); return a.length >= 8;
  }

  const json = express.json({ limit: '12mb' });

  app.post('/api/login', json, (req, res) => {
    const username = String(req.body?.username || '').trim().toLowerCase();
    const password = String(req.body?.password || '');
    const key = `${req.ip}|${username}`;
    if (throttled(key)) return res.status(429).json({ error: 'Muitas tentativas. Aguarde alguns minutos.' });
    const u = q('SELECT * FROM users WHERE username = ?').get(username);
    const ok = u && u.active === 1 && verifyPassword(password, u.pass);
    if (!ok) { attempts.get(key).push(Date.now()); return res.status(401).json({ error: 'Usuário ou senha inválidos' }); }
    attempts.delete(key);
    q('DELETE FROM sessions WHERE expires < ?').run(Date.now());
    const token = crypto.randomBytes(32).toString('base64url');
    q('INSERT INTO sessions(token, userId, expires) VALUES (?,?,?)').run(sha(token), u.id, Date.now() + SESSION_DAYS * 86_400_000);
    setCookie(req, res, token, SESSION_DAYS * 86400);
    res.json({ id: u.id, username: u.username, name: u.name, role: u.role });
  });

  app.post('/api/logout', (req, res) => {
    const t = cookies(req).sid; if (t) q('DELETE FROM sessions WHERE token = ?').run(sha(t));
    setCookie(req, res, '', 0); res.json({ ok: true });
  });

  app.get('/api/me', auth, (req, res) => res.json(req.user));

  // ---- painel de administração (usuários) ----
  const pubUser = (u) => ({ id: u.id, username: u.username, name: u.name, role: u.role, active: !!u.active, createdAt: u.createdAt,
    surveys: q('SELECT COUNT(*) c FROM surveys WHERE ownerId = ? AND json_extract(json, \'$.deleted\') = 0').get(u.id).c });
  const activeAdmins = () => q('SELECT COUNT(*) c FROM users WHERE role = \'admin\' AND active = 1').get().c;

  app.get('/api/admin/users', auth, adminOnly, (req, res) => res.json(q('SELECT * FROM users ORDER BY username').all().map(pubUser)));

  app.post('/api/admin/users', auth, adminOnly, json, (req, res) => {
    const b = req.body || {}; const username = String(b.username || '').trim().toLowerCase();
    const role = b.role === 'admin' ? 'admin' : 'tecnico';
    if (!validUsername(username)) return res.status(400).json({ error: 'Usuário: 3 a 32 caracteres (letras minúsculas, números, . _ -)' });
    if (!validPassword(b.password)) return res.status(400).json({ error: 'A senha precisa ter ao menos 8 caracteres' });
    if (q('SELECT 1 FROM users WHERE username = ?').get(username)) return res.status(409).json({ error: 'Esse usuário já existe' });
    const r = q('INSERT INTO users(username, name, pass, role, active, createdAt) VALUES (?,?,?,?,1,?) RETURNING *')
      .get(username, String(b.name || '').trim().slice(0, 80), hashPassword(b.password), role, Date.now());
    res.status(201).json(pubUser(r));
  });

  app.patch('/api/admin/users/:id', auth, adminOnly, json, (req, res) => {
    const id = Number(req.params.id); const u = q('SELECT * FROM users WHERE id = ?').get(id);
    if (!u) return res.status(404).json({ error: 'Usuário não encontrado' });
    const b = req.body || {};
    const role = b.role === undefined ? u.role : (b.role === 'admin' ? 'admin' : 'tecnico');
    const active = b.active === undefined ? u.active : (b.active ? 1 : 0);
    if (u.role === 'admin' && u.active && (role !== 'admin' || !active) && activeAdmins() <= 1)
      return res.status(400).json({ error: 'Precisa existir ao menos um administrador ativo' });
    if (b.password !== undefined && !validPassword(b.password)) return res.status(400).json({ error: 'A senha precisa ter ao menos 8 caracteres' });
    tx(() => {
      q('UPDATE users SET name = ?, role = ?, active = ?, pass = ? WHERE id = ?')
        .run(b.name === undefined ? u.name : String(b.name).trim().slice(0, 80), role, active, b.password !== undefined ? hashPassword(b.password) : u.pass, id);
      if (!active || b.password !== undefined) q('DELETE FROM sessions WHERE userId = ?').run(id); // derruba sessões abertas
    });
    res.json(pubUser(q('SELECT * FROM users WHERE id = ?').get(id)));
  });

  app.delete('/api/admin/users/:id', auth, adminOnly, (req, res) => {
    const id = Number(req.params.id); const u = q('SELECT * FROM users WHERE id = ?').get(id);
    if (!u) return res.status(404).json({ error: 'Usuário não encontrado' });
    if (id === req.user.id) return res.status(400).json({ error: 'Você não pode excluir a si mesmo' });
    if (u.role === 'admin' && u.active && activeAdmins() <= 1) return res.status(400).json({ error: 'Precisa existir ao menos um administrador ativo' });
    if (q('SELECT 1 FROM surveys WHERE ownerId = ? LIMIT 1').get(id))
      return res.status(409).json({ error: 'Esse usuário tem levantamentos. Desative-o em vez de excluir.' });
    q('DELETE FROM users WHERE id = ?').run(id); res.json({ ok: true });
  });


  // ---- sincronização (cada usuário só lê/escreve o que é seu) ----
  // Conflito: vence o registro com maior updatedAt. A paginação usa um contador global (seq), imune a relógios errados.
  app.post('/api/sync', auth, json, (req, res) => {
    const uid = req.user.id; const body = req.body || {}; const since = Number(body.since) || 0;
    const inS = Array.isArray(body.surveys) ? body.surveys.slice(0, 2000) : [];
    const inM = Array.isArray(body.markers) ? body.markers.slice(0, 20000) : [];
    tx(() => {
      for (const s of inS) {
        if (typeof s?.id !== 'string' || s.id.length > 64) continue;
        const cur = q('SELECT ownerId, updatedAt FROM surveys WHERE id = ?').get(s.id);
        if (cur && cur.ownerId !== uid) continue;                       // id de outro usuário: ignorado
        if (cur && cur.updatedAt > Number(s.updatedAt)) continue;       // versão do servidor é mais nova
        const clean = cleanSurvey(s, req.user.username, req.user.name || req.user.username);
        q('INSERT INTO surveys(id, ownerId, seq, updatedAt, json) VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET seq=excluded.seq, updatedAt=excluded.updatedAt, json=excluded.json')
          .run(clean.id, uid, nextSeq(), clean.updatedAt, JSON.stringify(clean));
      }
      for (const m of inM) {
        if (typeof m?.id !== 'string' || m.id.length > 64 || typeof m.surveyId !== 'string') continue;
        const parent = q('SELECT ownerId FROM surveys WHERE id = ?').get(m.surveyId);
        if (!parent || parent.ownerId !== uid) continue;                // marcador só em levantamento próprio
        const cur = q('SELECT ownerId, updatedAt FROM markers WHERE id = ?').get(m.id);
        if (cur && (cur.ownerId !== uid || cur.updatedAt > Number(m.updatedAt))) continue;
        const clean = cleanMarker(m);
        q('INSERT INTO markers(id, surveyId, ownerId, seq, updatedAt, json) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET seq=excluded.seq, updatedAt=excluded.updatedAt, json=excluded.json')
          .run(clean.id, clean.surveyId, uid, nextSeq(), clean.updatedAt, JSON.stringify(clean));
      }
    });
    const seq = q('SELECT v FROM meta WHERE k = \'seq\'').get().v;
    res.json({
      seq,
      surveys: q('SELECT json FROM surveys WHERE ownerId = ? AND seq > ? ORDER BY seq').all(uid, since).map((r) => JSON.parse(r.json)),
      markers: q('SELECT json FROM markers WHERE ownerId = ? AND seq > ? ORDER BY seq').all(uid, since).map((r) => JSON.parse(r.json)),
    });
  });

  const CATS = ['SIGN', 'EMERGENCY_LIGHT', 'EXTINGUISHER', 'HYDRANT', 'SPRINKLER', 'ALARM', 'OTHER'];
  const STATS = ['MISSING', 'IRREGULAR', 'OK'];
  const str = (v, n) => String(v ?? '').slice(0, n);
  const num = (v, lo, hi, d = 0) => { v = Number(v); return Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d; };
  const blobId = (v) => (typeof v === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(v) ? v : null);
  function cleanSurvey(s, owner, ownerName) {
    return { id: str(s.id, 64), company: str(s.company, 120), client: str(s.client, 160),
      createdAt: num(s.createdAt, 0, 4e12, Date.now()), pdfBlob: blobId(s.pdfBlob), pageCount: Math.round(num(s.pageCount, 1, 500, 1)),
      createdBy: owner, createdByName: str(ownerName, 80), updatedAt: num(s.updatedAt, 0, 4e12, Date.now()), deleted: s.deleted ? 1 : 0 };
  }
  function cleanMarker(m) {
    return { id: str(m.id, 64), surveyId: str(m.surveyId, 64), page: Math.round(num(m.page, 0, 499)), x: num(m.x, 0, 1), y: num(m.y, 0, 1),
      category: CATS.includes(m.category) ? m.category : 'OTHER', spec: str(m.spec, 200), status: STATS.includes(m.status) ? m.status : 'MISSING',
      note: str(m.note, 2000), photoBlob: blobId(m.photoBlob), createdAt: num(m.createdAt, 0, 4e12, Date.now()),
      updatedAt: num(m.updatedAt, 0, 4e12, Date.now()), deleted: m.deleted ? 1 : 0 };
  }

  // ---- arquivos (PDF da planta e fotos) ----
  app.put('/api/blobs/:id', auth, express.raw({ type: () => true, limit: MAX_BLOB }), (req, res) => {
    const id = blobId(req.params.id); if (!id) return res.status(400).json({ error: 'id inválido' });
    const cur = q('SELECT ownerId FROM blobs WHERE id = ?').get(id);
    if (cur && cur.ownerId !== req.user.id) return res.status(403).json({ error: 'Proibido' });
    if (!Buffer.isBuffer(req.body) || !req.body.length) return res.status(400).json({ error: 'corpo vazio' });
    const type = ['application/pdf', 'image/jpeg', 'image/png'].includes(req.headers['content-type']) ? req.headers['content-type'] : 'application/octet-stream';
    fs.writeFileSync(path.join(blobDir, id), req.body);
    q('INSERT INTO blobs(id, ownerId, type, size) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type, size=excluded.size').run(id, req.user.id, type, req.body.length);
    res.json({ ok: true });
  });

  app.get('/api/blobs/:id', auth, (req, res) => {
    const id = blobId(req.params.id); if (!id) return res.status(400).end();
    const b = q('SELECT ownerId, type FROM blobs WHERE id = ?').get(id);
    if (!b || b.ownerId !== req.user.id) return res.status(404).end();   // de outro usuário = "não existe"
    res.setHeader('Content-Type', b.type);
    res.setHeader('Cache-Control', 'private, max-age=31536000, immutable');
    fs.createReadStream(path.join(blobDir, id)).pipe(res);
  });

  app.use('/api', (req, res) => res.status(404).json({ error: 'Não encontrado' }));
  app.use((err, req, res, next) => { // eslint-disable-line no-unused-vars
    if (err?.type === 'entity.too.large') return res.status(413).json({ error: 'Arquivo grande demais' });
    if (err instanceof SyntaxError) return res.status(400).json({ error: 'JSON inválido' });
    console.error(err); res.status(500).json({ error: 'Erro interno' });
  });

  // ---- front-end estático (build do Vite) ----
  const dist = path.join(__dirname, '..', 'dist');
  if (fs.existsSync(dist)) {
    app.use(express.static(dist, { setHeaders: (res, p) => { if (/sw\.js$|index\.html$|manifest/.test(p)) res.setHeader('Cache-Control', 'no-cache'); } }));
    app.get('*', (req, res) => res.sendFile(path.join(dist, 'index.html')));
  }
  return { app, db };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { app } = createApp();
  app.listen(PORT, () => console.log(`[ppci] ouvindo na porta ${PORT} · dados em ${DATA_DIR}`));
}
