import { useState } from 'react';
import { MockBadge } from './ui';

/*
 * Ban / suspend / activate -- VISIBLE, and deliberately NOT WIRED.
 *
 * There is no users.status column and no migration behind these (deferred.md §L.20): the
 * scope was cut for the demo. What they do is change this component's own state, which is
 * enough to show what the control would feel like and nothing more.
 *
 * The badge is not an apology, it is the design. A panel that quietly pretended to ban
 * someone would be worse than one that says it cannot -- and the person giving the demo
 * should never have to remember which buttons are real.
 */

type Status = 'ACTIVE' | 'SUSPENDED' | 'BANNED';

const LABEL: Record<Status, string> = {
  ACTIVE: 'Aktif',
  SUSPENDED: 'Askıda',
  BANNED: 'Yasaklı',
};

const TONE: Record<Status, string> = {
  ACTIVE: 'real',
  SUSPENDED: 'mock',
  BANNED: 'missing',
};

export function AccountActions() {
  const [status, setStatus] = useState<Status>('ACTIVE');

  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="row">
        <span className="kpi-label" style={{ margin: 0 }}>
          Hesap durumu
        </span>
        <span className={`badge ${TONE[status]}`}>{LABEL[status]}</span>
      </div>

      <div className="row" style={{ flexWrap: 'wrap' }}>
        <button onClick={() => setStatus('SUSPENDED')} disabled={status === 'SUSPENDED'}>
          Askıya al
        </button>
        <button className="danger" onClick={() => setStatus('BANNED')} disabled={status === 'BANNED'}>
          Yasakla
        </button>
        <button onClick={() => setStatus('ACTIVE')} disabled={status === 'ACTIVE'}>
          Aktifleştir
        </button>
      </div>

      <p className="sub" style={{ lineHeight: 1.5 }}>
        <MockBadge>veritabanına yazılmıyor</MockBadge>{' '}
        Bu değişiklik yalnız ekranda duruyor; sayfayı yenileyince geri döner.{' '}
        <code>users.status</code> kolonu bu faz kapsamında yazılmadı.
      </p>
    </div>
  );
}
