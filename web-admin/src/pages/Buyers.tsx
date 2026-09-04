import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { BuyerRow } from '../api/types';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, money } from '../components/format';
import { ErrorState, Loading, Section, useApi } from '../components/ui';

export function Buyers() {
  const navigate = useNavigate();
  const { data, error } = useApi(() => api.get<BuyerRow[]>('/admin/buyers'));

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  const debt = data.reduce((total, row) => total + row.debt_minor, 0);
  const owing = data.filter((row) => row.debt_minor > 0);
  const dualRole = data.filter((row) => row.is_seller).length;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Alıcılar</h1>
          <p className="sub">
            Her kişi tek kimlik, ama her dükkânda ayrı defter kaydı — buradaki borç o
            kayıtların toplamı.
          </p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Toplam borç" value={money(debt)} tone="neg" />
        <Kpi index={1} label="Hesap" value={count(data.length)} note={`${dualRole} tanesi aynı zamanda satıcı`} />
        <Kpi index={2} label="Borcu olan" value={count(owing.length)} />
        <Kpi
          index={3}
          label="Ortalama borç"
          value={money(owing.length ? Math.round(debt / owing.length) : 0)}
          note="borcu olan hesap başına"
        />
      </KpiRow>

      <Section title="Hesaplar">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Ad</th>
                <th>Telefon</th>
                <th>Rol</th>
                <th className="num">Dükkân</th>
                <th className="num">Borç</th>
              </tr>
            </thead>
            <tbody>
              {data.map((row) => (
                <tr
                  key={row.user_id}
                  className="clickable"
                  onClick={() => navigate(`/buyers/${row.user_id}`)}
                >
                  <td>{row.display_name}</td>
                  <td className="money">{row.phone}</td>
                  <td>{row.is_seller ? 'Alıcı + satıcı' : 'Alıcı'}</td>
                  <td className="num">{count(row.shop_count)}</td>
                  <td className={`num ${row.debt_minor > 0 ? 'neg' : ''}`}>
                    {money(row.debt_minor)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  );
}
