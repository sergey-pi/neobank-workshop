import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { login as loginUser } from '../api/userApi.js';
import { useAuth } from '../auth/authContext.jsx';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { auth, login } = useAuth();
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const from = location.state?.from ?? { pathname: '/dashboard' };
  const set = (field) => (e) => setForm((current) => ({ ...current, [field]: e.target.value }));

  if (auth) {
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const response = await loginUser(form);
      login(response);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err?.detail ?? err?.message ?? 'Sign in failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <h1 className="page-title">Sign in</h1>
      <div className="card">
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Email</label>
            <input type="email" required value={form.email} onChange={set('email')} />
          </div>
          <div className="form-group">
            <label>Password</label>
            <input type="password" required value={form.password} onChange={set('password')} />
          </div>
          {error && <div className="alert alert-error">{error}</div>}
          <div className="mt-16">
            <button className="btn btn-primary" type="submit" disabled={loading}>
              {loading ? 'Signing in…' : 'Sign In'}
            </button>
          </div>
        </form>

        <p className="text-muted mt-16">
          Don't have an account? <Link to="/register">Register</Link>
        </p>
      </div>
    </>
  );
}
