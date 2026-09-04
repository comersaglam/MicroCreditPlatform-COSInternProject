import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, storeToken } from '../api/client';
import type { AdminSession } from '../api/types';

export function Login() {
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await api.post<AdminSession>('/admin/login', { password });
      storeToken(session.token);
      navigate('/buyers');
    } catch (cause) {
      // The server's own message, not one invented here: it is the thing that says why.
      setError(cause instanceof ApiError ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      {/* The one trail outside a KPI row. This is the whole screen, so it is the focus by
          definition rather than by decoration. */}
      <form className="card edge-trail login-card" onSubmit={submit}>
        <div className="brand" style={{ padding: '0 0 20px' }}>
          <img src="/tfides-logo.png" alt="T-Fides" />
          <div>
            <div className="brand-name">T-Fides</div>
            <div className="brand-role">Admin paneli</div>
          </div>
        </div>

        <label className="kpi-label" htmlFor="password">
          Yönetici şifresi
        </label>
        <input
          id="password"
          type="password"
          autoFocus
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          placeholder="••••••"
        />

        <button
          className="primary"
          type="submit"
          disabled={busy || !password}
          style={{ width: '100%', marginTop: 14 }}
        >
          {busy ? 'Kontrol ediliyor…' : 'Giriş'}
        </button>

        {error && <p className="error-text">{error}</p>}

        <p className="sub" style={{ marginTop: 18, lineHeight: 1.5 }}>
          Bu panel uygulamalardan ayrı bir kimlikle çalışır — telefon ve POS oturumları
          buraya giriş yapamaz.
        </p>
      </form>
    </div>
  );
}
