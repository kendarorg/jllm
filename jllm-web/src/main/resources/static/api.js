export async function api(path, opts = {}) {
  const r = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts));
  if (!r.ok) {
    let msg = 'HTTP ' + r.status;
    try { msg = (await r.json()).error || msg; } catch (e) { /* not json */ }
    throw new Error(msg);
  }
  return r.status === 204 ? null : r.json();
}

export const post = (path, body) => api(path, { method: 'POST', body: JSON.stringify(body ?? {}) });
