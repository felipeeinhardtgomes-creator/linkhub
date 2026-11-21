import crypto from 'node:crypto';

// scrypt com sal por senha. Formato: scrypt$<salt hex>$<hash hex>
export function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  return `scrypt$${salt.toString('hex')}$${crypto.scryptSync(pw, salt, 64).toString('hex')}`;
}

export function verifyPassword(pw, stored) {
  const [kind, saltHex, hashHex] = String(stored).split('$');
  if (kind !== 'scrypt' || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const got = crypto.scryptSync(String(pw), Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(got, expected);
}

export const validUsername = (u) => /^[a-z0-9._-]{3,32}$/.test(u);
export const validPassword = (p) => typeof p === 'string' && p.length >= 8 && p.length <= 200;
