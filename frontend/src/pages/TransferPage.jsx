import { useState } from 'react';
import { transfer } from '../api/ledgerApi.js';

function formatAmount(minor) {
  return (minor / 100).toLocaleString('en-US', { minimumFractionDigits: 2 });
}

export default function TransferPage() {
  const [form, setForm] = useState({
    fromAccountId: '', toAccountId: '',
    amount: '', currency: 'USD', description: '',
  });
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const set = (f) => (e) => setForm((s) => ({ ...s, [f]: e.target.value }));

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      // amount field is in major units (e.g. 10.50); convert to minor units (cents)
      const amountMinor = Math.round(parseFloat(form.amount) * 100);
      const res = await transfer({ ...form, amount: amountMinor });
      setResult(res);
    } catch (err) {
      const msg = err?.detail ?? err?.message ?? 'Transfer failed';
      const status = err?.status;
      if (status === 403) setError(`KYC not approved: ${msg}`);
      else if (status === 422) setError(`Limit exceeded: ${msg}`);
      else if (status === 503) setError(`KYC service unavailable: ${msg}`);
      else setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <h1 className="page-title">Transfer funds</h1>
      <div className="card">
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>From account ID</label>
            <input required value={form.fromAccountId} onChange={set('fromAccountId')} placeholder="UUID" />
          </div>
          <div className="form-group">
            <label>To account ID</label>
            <input required value={form.toAccountId} onChange={set('toAccountId')} placeholder="UUID" />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>Amount ({form.currency})</label>
              <input
                type="number" required min="0.01" step="0.01"
                value={form.amount} onChange={set('amount')}
                placeholder="0.00"
              />
            </div>
            <div className="form-group">
              <label>Currency</label>
              <input maxLength={3} required value={form.currency} onChange={set('currency')} />
            </div>
          </div>
          <div className="form-group">
            <label>Description (optional)</label>
            <input value={form.description} onChange={set('description')} />
          </div>

          {error && <div className="alert alert-error">{error}</div>}
          {result && (
            <div className="alert alert-success">
              Transfer {result.status} — transaction {String(result.transactionId).slice(0, 8)}…
            </div>
          )}

          <div className="mt-16">
            <button className="btn btn-primary" type="submit" disabled={loading}>
              {loading ? 'Sending…' : `Transfer ${form.amount ? formatAmount(Math.round(parseFloat(form.amount || 0) * 100)) : '0.00'} ${form.currency}`}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
