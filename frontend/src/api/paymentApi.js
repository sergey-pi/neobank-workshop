// Thin fetch wrappers for payment-service (port 8083, proxied via Vite)

import { parseApiError } from './apiUtils.js';

export async function createPayment(data) {
  const res = await fetch('/api/v1/payments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}

export async function getPayments() {
  const res = await fetch('/api/v1/payments');
  if (!res.ok) throw await parseApiError(res);
  return res.json();
}
