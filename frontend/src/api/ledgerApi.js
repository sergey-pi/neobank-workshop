// Thin fetch wrappers for ledger-service (port 8082, proxied via Vite)

import { parseApiError, fetchWithTimeout } from './apiUtils.js';

export async function createAccount(data) {
  const res = await fetchWithTimeout('/api/v1/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getAccounts() {
  const res = await fetchWithTimeout('/api/v1/accounts');
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function transfer(data) {
  const res = await fetchWithTimeout('/api/v1/transactions/transfer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getTransactions(page = 0, size = 20) {
  const res = await fetchWithTimeout(`/api/v1/transactions?page=${page}&size=${size}`);
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}
