// Thin fetch wrappers for user-service (port 8081, proxied via Vite)

const BASE = '/api/v1/users';

export async function registerUser(data) {
  const res = await fetch(`${BASE}/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function getUsers() {
  const res = await fetch(BASE);
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function getKycStatus(userId) {
  const res = await fetch(`${BASE}/${userId}/kyc-status`);
  if (!res.ok) throw await res.json();
  return res.json();
}
