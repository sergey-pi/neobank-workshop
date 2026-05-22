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
