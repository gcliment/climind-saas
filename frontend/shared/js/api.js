// Cliente de la API /api/v1.
// - La sesión viaja en una cookie HttpOnly (el JS nunca ve tokens de sesión; nada en localStorage).
// - CSRF: la cookie XSRF-TOKEN se reenvía en el header X-XSRF-TOKEN en cada operación que modifica datos.

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

/** Error de la API con el formato ApiError del backend. */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || 'No pudimos completar la operación. Probá de nuevo.');
    this.status = status;
    this.code = body?.code || 'UNKNOWN';
    this.fieldErrors = body?.fieldErrors || [];
    this.issues = body?.issues || [];
    this.traceId = body?.traceId;
  }
}

function readCookie(name) {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.split('=')[1]) : null;
}

async function ensureCsrfToken() {
  let token = readCookie(CSRF_COOKIE);
  if (!token) {
    await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
    token = readCookie(CSRF_COOKIE);
  }
  return token;
}

async function request(method, path, body, { retryCsrf = true } = {}) {
  const headers = { Accept: 'application/json' };
  const mutating = method !== 'GET' && method !== 'HEAD';
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (mutating) headers[CSRF_HEADER] = await ensureCsrfToken();

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, { code: 'NETWORK', message: 'No hay conexión con el servidor. Revisá tu internet y probá de nuevo.' });
  }

  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (response.ok) return data;

  // Token CSRF rotado (por ejemplo, tras iniciar sesión en otra pestaña): se pide uno nuevo y se reintenta una vez.
  if (response.status === 403 && data?.code === 'CSRF_INVALID' && retryCsrf) {
    document.cookie = `${CSRF_COOKIE}=; Max-Age=0; path=/`;
    return request(method, path, body, { retryCsrf: false });
  }
  throw new ApiError(response.status, data);
}

/** Subida de archivos (multipart). El navegador arma el Content-Type con el boundary. */
async function upload(path, formData) {
  const response = await fetch(path, {
    method: 'POST',
    body: formData,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', [CSRF_HEADER]: await ensureCsrfToken() },
  }).catch(() => {
    throw new ApiError(0, { code: 'NETWORK', message: 'No hay conexión con el servidor. Revisá tu internet y probá de nuevo.' });
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body ?? {}),
  put: (path, body) => request('PUT', path, body ?? {}),
  del: (path) => request('DELETE', path),
  upload,
};

/** Usuario actual o null si no hay sesión. */
export async function currentUser() {
  try {
    return await api.get('/api/v1/auth/me');
  } catch (e) {
    if (e.status === 401) return null;
    throw e;
  }
}
