export const authEvents = new EventTarget();

export async function api(method, url, body) {
  const r = await fetch(url, {
    method, credentials: 'same-origin',
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await r.json().catch(() => ({}));
  if (r.status === 401 && !url.endsWith('/login')) authEvents.dispatchEvent(new Event('expired'));
  if (!r.ok) throw Object.assign(new Error(data.error || `Erro ${r.status}`), { status: r.status });
  return data;
}
export const getJSON = (u) => api('GET', u);
export const postJSON = (u, b) => api('POST', u, b ?? {});
