import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { registerUser } from '../api/userApi.js';

export default function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    email: '', password: '', firstName: '', lastName: '',
    phoneNumber: '', dateOfBirth: '', countryCode: 'US',
    addressLine1: '', city: '', postalCode: '',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);

  const set = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  // Navigate to login after successful registration; effect handles cleanup on unmount.
  useEffect(() => {
    if (!success) return;
    const t = setTimeout(() => navigate('/login'), 2000);
    return () => clearTimeout(t);
  }, [success, navigate]);

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await registerUser(form);
      setSuccess('Account created! Redirecting to sign in…');
    } catch (err) {
      setError(err?.detail ?? err?.message ?? 'Registration failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <h1 className="page-title">Create account</h1>
      <div className="card">
        <form onSubmit={handleSubmit}>
          <div className="form-row">
            <div className="form-group">
              <label>First name</label>
              <input required value={form.firstName} onChange={set('firstName')} />
            </div>
            <div className="form-group">
              <label>Last name</label>
              <input required value={form.lastName} onChange={set('lastName')} />
            </div>
          </div>
          <div className="form-group">
            <label>Email</label>
            <input type="email" required value={form.email} onChange={set('email')} />
          </div>
          <div className="form-group">
            <label>Password (min 8 chars)</label>
            <input type="password" required minLength={8} value={form.password} onChange={set('password')} />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>Phone number</label>
              <input required value={form.phoneNumber} onChange={set('phoneNumber')} />
            </div>
            <div className="form-group">
              <label>Date of birth</label>
              <input type="date" required value={form.dateOfBirth} onChange={set('dateOfBirth')} />
            </div>
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>Country code</label>
              <input required maxLength={2} value={form.countryCode} onChange={set('countryCode')} />
            </div>
            <div className="form-group">
              <label>Postal code</label>
              <input value={form.postalCode} onChange={set('postalCode')} />
            </div>
          </div>
          <div className="form-group">
            <label>Address</label>
            <input required value={form.addressLine1} onChange={set('addressLine1')} />
          </div>
          <div className="form-group">
            <label>City</label>
            <input value={form.city} onChange={set('city')} />
          </div>
          {error && <div className="alert alert-error">{error}</div>}
          {success && <div className="alert alert-success">{success}</div>}
          <div className="mt-16">
            <button className="btn btn-primary" type="submit" disabled={loading}>
              {loading ? 'Creating…' : 'Create account'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
