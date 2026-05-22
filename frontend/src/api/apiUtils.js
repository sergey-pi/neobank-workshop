/**
 * Parses an error response safely — handles non-JSON bodies (502, HTML error pages)
 * and always preserves the HTTP status code on the thrown error.
 */
export async function parseApiError(res) {
  let body = {};
  try {
    body = await res.json();
  } catch {
    // non-JSON body (nginx error page, plain text 500, etc.)
  }
  const err = new Error(body.detail ?? body.message ?? res.statusText ?? 'Request failed');
  err.status = res.status;
  Object.assign(err, body);
  return err;
}

/** Default request timeout in milliseconds. */
const DEFAULT_TIMEOUT_MS = 10_000;
const AUTH_STORAGE_KEYS = ['accessToken', 'userId', 'email'];

export function clearAuthSession() {
  AUTH_STORAGE_KEYS.forEach((key) => sessionStorage.removeItem(key));
}

export function getAuthHeader() {
  const token = sessionStorage.getItem('accessToken');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function handleUnauthorized() {
  clearAuthSession();
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
    window.location.replace('/login');
  }
}

/**
 * fetch() with an automatic AbortController timeout.
 * Throws a DOMException with name 'AbortError' if the timeout is exceeded.
 */
export async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (err) {
    if (err.name === 'AbortError') {
      const timeoutErr = new Error(`Request timed out after ${timeoutMs}ms`);
      timeoutErr.status = 408;
      throw timeoutErr;
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}
