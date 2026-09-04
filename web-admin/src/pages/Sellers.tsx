import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { SellerRow } from '../api/types';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, money } from '../components/format';
import { ErrorState, Loading, Section, useApi } from '../components/ui';

export function Sellers() {
  const navigate = useNavigate();
  const { data, error } = useApi(() => api.get<SellerRow[]>('/admin/sellers'));

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  const receivable = data.reduce((total, row) => total + row.receivable_minor, 0);
  const customers = data.reduce((total, row) => total + row.customer_count, 0);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Satıcılar</h1>
          <p className="sub">Platformdaki her dükkân ve o dükkânın alacağı.</p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Toplam alacak" value={money(receivable)} tone="neg" />
        <Kpi index={1} label="Dükkân" value={count(data.length)} />
        <Kpi index={2} label="Müşteri kaydı" value={count(customers)} />
        <Kpi
          index={3}
          label="Ortalama defter"
          value={money(data.length ? Math.round(receivable / data.length) : 0)}
          note="dükkân başına alacak"
        />
      </KpiRow>

      <Section title="Dükkânlar">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Dükkân</th>
                <th>Sahibi</th>
                <th>Telefon</th>
                <th className="num">Müşteri</th>
                <th className="num">Hareket</th>
                <th className="num">Alacak</th>
              </tr>
            </thead>
            <tbody>
              {data.map((row) => (
                <tr
                  key={row.user_id}
                  className="clickable"
                  onClick={() => navigate(`/sellers/${row.user_id}`)}
                >
                  <td>{row.shop_name ?? '—'}</td>
                  <td>{row.display_name}</td>
                  <td className="money">{row.phone}</td>
                  <td className="num">{count(row.customer_count)}</td>
                  <td className="num">{count(row.entry_count)}</td>
                  <td className="num neg">{money(row.receivable_minor)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  );
}
