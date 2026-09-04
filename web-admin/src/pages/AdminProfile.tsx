import { useState } from 'react';
import { api } from '../api/client';
import type { AdminMe } from '../api/types';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, day } from '../components/format';
import { ErrorState, Loading, Section, StatusBadge, useApi } from '../components/ui';

const TABLE_LABEL: Record<string, string> = {
  users: 'Kullanıcılar',
  customers: 'Müşteri kayıtları',
  transactions: 'Ledger satırları',
  baskets: 'Sepetler',
  basket_items: 'Sepet kalemleri',
  approvals: 'Onaylar',
  pgw_jobs: 'PGW işleri',
  fx_rates: 'Kur günleri',
  audit_log: 'Trafik kayıtları',
};

export function AdminProfile() {
  const { data, error, reload } = useApi(() => api.get<AdminMe>('/admin/me'));
  const [confirming, setConfirming] = useState(false);
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  async function reset() {
    setBusy(true);
    setResult(null);
    try {
      const after = await api.post<AdminMe>('/admin/reset?with_demo=true');
      setResult(
        `Sıfırlandı — ${count(after.counts.transactions)} ledger satırı, ` +
          `${count(after.counts.customers)} müşteri geri yüklendi.`,
      );
      setConfirming(false);
      setTyped('');
      reload();
    } catch (cause) {
      setResult(`Başarısız: ${String(cause)}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Admin profili</h1>
          <p className="sub">Oturum, veritabanı durumu ve bu panelde neyin gerçek olduğu.</p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Oturum" value={data.subject} note={`${data.expires_in_seconds / 60} dakika geçerli`} />
        <Kpi index={1} label="Migration" value={data.migration_head ?? '—'} />
        <Kpi index={2} label="Ledger satırı" value={count(data.counts.transactions ?? 0)} />
        <Kpi index={3} label="Veri sonu" value={day(data.data_through)} />
      </KpiRow>

      <div className="stack">
        <Section
          title="Bu panelde ne gerçek?"
          aside={<span className="sub">deferred.md §L</span>}
        >
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Bileşen</th>
                  <th>Durum</th>
                  <th>Not</th>
                </tr>
              </thead>
              <tbody>
                {data.inventory.map((item) => (
                  <tr key={item.name}>
                    <td>{item.name}</td>
                    <td>
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="sub">{item.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>

        <Section
          title="Veritabanı"
          aside={
            <span className="sub">
              {data.core_seeded ? 'çekirdek seed ✓' : 'çekirdek seed yok'} ·{' '}
              {data.demo_seeded ? 'demo verisi ✓' : 'demo verisi yok'}
            </span>
          }
        >
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Tablo</th>
                  <th className="num">Satır</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(data.counts).map(([table, rows]) => (
                  <tr key={table}>
                    <td>
                      {TABLE_LABEL[table] ?? table}{' '}
                      <span className="sub">({table})</span>
                    </td>
                    <td className="num">{count(rows)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>

        <Section title="Demo verisini sıfırla">
          <p className="sub" style={{ lineHeight: 1.6, marginBottom: 14 }}>
            Bütün tabloları boşaltır, sonra çekirdek seed'i ve zengin demo verisini geri
            yükler, en sonunda da endeksleme satırlarını yeniden işler. Sunum arasında
            sistemi temiz bir başlangıca döndürmek için.
          </p>

          {!confirming ? (
            <button className="danger" onClick={() => setConfirming(true)}>
              Sıfırla…
            </button>
          ) : (
            <div className="stack" style={{ gap: 12, maxWidth: 460 }}>
              <div className="notice" style={{ marginBottom: 0 }}>
                <span>⚠️</span>
                <div>
                  Şu an veritabanında{' '}
                  <strong>{count(data.counts.transactions ?? 0)} ledger satırı</strong> ve{' '}
                  <strong>{count(data.counts.customers ?? 0)} müşteri kaydı</strong> var.
                  Hepsi silinip demo verisi yeniden kurulacak. Onaylamak için{' '}
                  <code>SIFIRLA</code> yazın.
                </div>
              </div>
              <input
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                placeholder="SIFIRLA"
              />
              <div className="row">
                <button
                  className="danger"
                  disabled={typed !== 'SIFIRLA' || busy}
                  onClick={reset}
                >
                  {busy ? 'Sıfırlanıyor…' : 'Evet, sıfırla'}
                </button>
                <button
                  onClick={() => {
                    setConfirming(false);
                    setTyped('');
                  }}
                >
                  Vazgeç
                </button>
              </div>
            </div>
          )}

          {result && (
            <p className="sub" style={{ marginTop: 14 }}>
              {result}
            </p>
          )}
        </Section>
      </div>
    </>
  );
}
