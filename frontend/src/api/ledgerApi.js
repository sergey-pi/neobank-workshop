// Thin fetch wrappers for ledger-service (port 8082, proxied via Vite)

export async function createAccount(data) {
  const res = await fetch('/api/v1/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function getAccounts() {
  const res = await fetch('/api/v1/accounts');
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function transfer(data) {
  const res = await fetch('/api/v1/transactions/transfer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function getTransactions() {
  const res = await fetch('/api/v1/transactions');
  if (!res.ok) throw await res.json();
  return res.json();
}
