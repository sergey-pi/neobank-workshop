import { useState, useEffect } from 'react';
import { getAccounts, createAccount } from '../api/ledgerApi.js';
import { getKycStatus } from '../api/userApi.js';
import KycBadge from '../components/KycBadge.jsx';

function formatAmount(minor) {
  return (minor / 100).toLocaleString('en-US', { minimumFractionDigits: 2 });
}

export default function DashboardPage() {
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Create account form
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({ userId: '', currency: 'USD', name: '', type: 'LIABILITY' });
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);

  // KYC lookup
  const [kycUserId, setKycUserId] = useState('');
  const [kycResult, setKycResult] = useState(null);
  const [kycError, setKycError] = useState(null);

  const set = (f) => (e) => setForm((s) => ({ ...s, [f]: e.target.value }));

  async function load() {
    setLoading(true);
    try {
      const data = await getAccounts();
      setAccounts(data);
    } catch (err) {
      setError('Could not load accounts — is ledger-service running?');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  async function handleCreate(e) {
    e.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      await createAccount(form);
      setShowCreate(false);
      setForm({ userId: '', currency: 'USD', name: '', type: 'LIABILITY' });
      await load();
    } catch (err) {
      setCreateError(err?.detail ?? err?.message ?? 'Failed to create account');
    } finally {
      setCreating(false);
    }
  }

  async function checkKyc(e) {
    e.preventDefault();
    setKycResult(null);
    setKycError(null);
    try {
      const data = await getKycStatus(kycUserId);
      setKycResult(data);
    } catch (err) {
      setKycError(err?.detail ?? 'User not found');
    }
  }

  return (
    <>
      <h1 className="page-title">Dashboard</h1>

      {/* Accounts */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <span className="card-title" style={{ marginBottom: 0 }}>Accounts</span>
          <button className="btn btn-outline" onClick={() => setShowCreate(!showCreate)}>
            {showCreate ? 'Cancel' : '+ New account'}
          </button>
        </div>

        {showCreate && (
          <form onSubmit={handleCreate} style={{ marginBottom: 20, padding: 16, background: 'var(--color-bg)', borderRadius: 'var(--radius)' }}>
            <div className="form-row">
              <div className="form-group">
                <label>User ID (UUID)</label>
                <input required value={form.userId} onChange={set('userId')} placeholder="xxxxxxxx-xxxx-..." />
              </div>
              <div className="form-group">
                <label>Currency</label>
                <input required maxLength={3} value={form.currency} onChange={set('currency')} />
              </div>
            </div>
            <div className="form-row">
              <div className="form-group">
                <label>Name</label>
                <input value={form.name} onChange={set('name')} placeholder="My checking account" />
              </div>
              <div className="form-group">
                <label>Type</label>
                <select value={form.type} onChange={set('type')}>
                  <option value="LIABILITY">LIABILITY</option>
                  <option value="ASSET">ASSET</option>
                  <option value="EQUITY">EQUITY</option>
                </select>
              </div>
            </div>
            {createError && <div className="alert alert-error">{createError}</div>}
            <button className="btn btn-primary" type="submit" disabled={creating}>
              {creating ? 'Creating…' : 'Create'}
            </button>
          </form>
        )}

        {loading && <p className="text-muted">Loading…</p>}
        {error && <p className="alert alert-error">{error}</p>}
        {!loading && !error && accounts.length === 0 && (
          <p className="text-muted">No accounts yet. Create one above.</p>
        )}
        <div className="account-grid">
          {accounts.map((a) => (
            <div key={a.id} className="account-item">
              <div className="text-muted">{a.name ?? a.type}</div>
              <div className="account-amount">{formatAmount(a.available_amount ?? a.availableAmount ?? 0)} {a.currency}</div>
              <div className="account-meta">{a.status} · {String(a.id).slice(0, 8)}…</div>
            </div>
          ))}
        </div>
      </div>

      {/* KYC lookup */}
      <div className="card">
        <div className="card-title">KYC Status Check</div>
        <form onSubmit={checkKyc} style={{ display: 'flex', gap: 10 }}>
          <input
            required
            value={kycUserId}
            onChange={(e) => setKycUserId(e.target.value)}
            placeholder="User ID (UUID)"
            style={{ flex: 1 }}
          />
          <button className="btn btn-primary" type="submit">Check</button>
        </form>
        {kycError && <div className="alert alert-error mt-8">{kycError}</div>}
        {kycResult && (
          <div className="mt-8" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span className="text-muted">{kycResult.userId}</span>
            <KycBadge status={kycResult.kycStatus} />
          </div>
        )}
      </div>
    </>
  );
}
