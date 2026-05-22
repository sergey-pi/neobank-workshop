import { useState, useEffect } from 'react';
import { getTransactions } from '../api/ledgerApi.js';

function formatDate(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString();
}

export default function HistoryPage() {
  const [transactions, setTransactions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    setLoading(true);
    getTransactions()
      .then(setTransactions)
      .catch(() => setError('Could not load transactions — is ledger-service running?'))
      .finally(() => setLoading(false));
  }, []);

  return (
    <>
      <h1 className="page-title">Transaction history</h1>
      <div className="card">
        {loading && <p className="text-muted">Loading…</p>}
        {error && <div className="alert alert-error">{error}</div>}
        {!loading && !error && transactions.length === 0 && (
          <p className="text-muted">No transactions yet.</p>
        )}
        {transactions.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Type</th>
                <th>Status</th>
                <th>Reference</th>
                <th>Description</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((tx) => (
                <tr key={tx.id}>
                  <td className="text-muted">{String(tx.id).slice(0, 8)}…</td>
                  <td>{tx.type}</td>
                  <td>{tx.status}</td>
                  <td className="text-muted">{tx.reference ?? '—'}</td>
                  <td className="text-muted">{tx.description ?? '—'}</td>
                  <td className="text-muted">{formatDate(tx.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
