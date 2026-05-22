// Thin fetch wrappers for user-service (port 8081, proxied via Vite)

import { parseApiError, fetchWithTimeout } from './apiUtils.js';

const BASE = '/api/v1/users';

export async function registerUser(data) {
  const res = await fetchWithTimeout(`${BASE}/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getUsers() {
  const res = await fetchWithTimeout(BASE);
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getKycStatus(userId) {
  const res = await fetchWithTimeout(`${BASE}/${userId}/kyc-status`);
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}
