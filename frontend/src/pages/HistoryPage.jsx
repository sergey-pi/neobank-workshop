import { useState, useEffect } from 'react';
import { getTransactions } from '../api/ledgerApi.js';

function formatDate(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString();
}

function formatAmount(tx) {
  if (tx?.amount == null) return '—';
  const prefix = tx.direction === 'CREDIT' ? '+' : '-';
  const currency = tx.currency ?? '';
  return `${prefix}${(tx.amount / 100).toFixed(2)} ${currency}`.trim();
}

function amountColor(direction) {
  return direction === 'CREDIT' ? 'green' : 'red';
}

export default function HistoryPage() {
  const [transactions, setTransactions] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [totalItems, setTotalItems] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let isMounted = true;
    setLoading(true);
    getTransactions(page, 20)
      .then((data) => {
        if (!isMounted) return;
        // Backend returns PagedResponse — if page > 0, append; otherwise replace
        setTransactions((prev) => page === 0 ? data.items : [...prev, ...data.items]);
        setHasMore(data.hasMore);
        setTotalItems(data.totalItems);
      })
      .catch(() => { if (isMounted) setError('Could not load transactions — is ledger-service running?'); })
      .finally(() => { if (isMounted) setLoading(false); });
    return () => { isMounted = false; };
  }, [page]);

  return (
    <>
      <h1 className="page-title">Transaction history</h1>
      <div className="card">
        {error && <div className="alert alert-error">{error}</div>}
        {!loading && !error && transactions.length === 0 && (
          <p className="text-muted">No transactions yet.</p>
        )}
        {transactions.length > 0 && (
          <>
            <p className="text-muted" style={{ marginBottom: 8 }}>
              Showing {transactions.length} of {totalItems} transactions
            </p>
            <table>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Reference</th>
                  <th>Description</th>
                  <th>Amount</th>
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
                    <td style={{ color: amountColor(tx.direction), fontWeight: 600 }}>{formatAmount(tx)}</td>
                    <td className="text-muted">{formatDate(tx.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {hasMore && (
              <div className="mt-16" style={{ textAlign: 'center' }}>
                <button
                  className="btn btn-outline"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={loading}
                >
                  {loading ? 'Loading…' : 'Load more'}
                </button>
              </div>
            )}
          </>
        )}
        {loading && <p className="text-muted">Loading…</p>}
      </div>
    </>
  );
}
