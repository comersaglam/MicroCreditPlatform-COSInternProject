import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';

/* Small shared pieces: loading/error states, the honesty badge, and the fetch hook every
   page uses. */

export function useApi<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    load()
      .then((result) => !cancelled && setData(result))
      .catch((cause: unknown) => {
        if (cancelled) return;
        // ApiError already carries the server's own message, which is the one worth
        // showing -- it says WHY, where a status code only says no.
        setError(cause instanceof ApiError ? cause.message : String(cause));
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadKey]);

  return { data, error, reload: () => setReloadKey((key) => key + 1) };
}

export function Loading() {
  return <div className="state">Yükleniyor…</div>;
}

export function ErrorState({ message }: { message: string }) {
  return <div className="state">Veri alınamadı: {message}</div>;
}

/**
 * Says a figure or a control is not live.
 *
 * The panel is honest about itself on purpose. Turn 46 did the same on the phone -- the
 * insights screen carries a line saying its numbers are examples -- so that whoever is
 * presenting never has to remember which figure is real. deferred.md §L is the long form;
 * this badge is the short one.
 */
export function MockBadge({ children = 'mock' }: { children?: ReactNode }) {
  return <span className="badge mock">● {children}</span>;
}

export function StatusBadge({ status }: { status: 'REAL' | 'MOCK' | 'MISSING' }) {
  const label = { REAL: 'gerçek', MOCK: 'mock', MISSING: 'yok' }[status];
  return <span className={`badge ${status.toLowerCase()}`}>{label}</span>;
}

export function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <div className="panel" style={{ padding: 20 }}>
      <div className="spread" style={{ marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>{title}</h2>
        {aside}
      </div>
      {children}
    </div>
  );
}
