// Thin fetch wrappers for user-service (port 8081, proxied via Vite)

import { getAuthHeader, handleUnauthorized, parseApiError, fetchWithTimeout } from './apiUtils.js';

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

export async function login(data) {
  const res = await fetchWithTimeout(`${BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getUsers() {
  const res = await fetchWithTimeout(BASE, {
    headers: { ...getAuthHeader() },
  });
  if (res.status === 401) handleUnauthorized();
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getKycStatus(userId) {
  const res = await fetchWithTimeout(`${BASE}/${userId}/kyc-status`, {
    headers: { ...getAuthHeader() },
  });
  if (res.status === 401) handleUnauthorized();
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}
