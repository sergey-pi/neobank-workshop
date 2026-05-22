// Thin fetch wrappers for payment-service (port 8083, proxied via Vite)

import { getAuthHeader, handleUnauthorized, parseApiError, fetchWithTimeout } from './apiUtils.js';

export async function createPayment(data) {
  const res = await fetchWithTimeout('/api/v1/payments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...getAuthHeader() },
    body: JSON.stringify(data),
  });
  if (res.status === 401) handleUnauthorized();
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getPayments() {
  const res = await fetchWithTimeout('/api/v1/payments', {
    headers: { ...getAuthHeader() },
  });
  if (res.status === 401) handleUnauthorized();
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}
