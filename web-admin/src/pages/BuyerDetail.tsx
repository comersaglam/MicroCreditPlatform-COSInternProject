import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { BuyerDetail as Detail } from '../api/types';
import { AccountActions } from '../components/AccountActions';
import { Entries } from '../components/Entries';
import { Kpi, KpiRow } from '../components/Kpi';
import { day, money } from '../components/format';
import { ErrorState, Loading, Section, useApi } from '../components/ui';

export function BuyerDetail() {
  const { userId } = useParams();
  const navigate = useNavigate();
  const { data, error } = useApi(
    () => api.get<Detail>(`/admin/buyers/${userId}`),
    [userId],
  );

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  const { buyer, breakdown, debts_by_shop, recent_entries } = data;

  return (
    <>
      <div className="page-head">
        <div>
          <Link className="back" to="/buyers">
            ← Alıcılar
          </Link>
          <h1 style={{ marginTop: 8 }}>{buyer.display_name}</h1>
          <p className="sub">
            {buyer.phone} · {buyer.is_seller ? 'alıcı + satıcı' : 'alıcı'} ·{' '}
            {day(buyer.created_at)} tarihinden beri
          </p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Toplam borç" value={money(breakdown.outstanding_minor)} tone="neg" />
        <Kpi index={1} label="Borçlu olduğu dükkân" value={String(debts_by_shop.length)} />
        <Kpi index={2} label="Enflasyon farkı" value={money(breakdown.indexation_minor)} />
        <Kpi
          index={3}
          label="3 ay sonra"
          value={breakdown.projected_3m_minor != null ? money(breakdown.projected_3m_minor) : '—'}
          note="bugün ödenmezse"
        />
      </KpiRow>

      <div className="stack">
        <Section title="Dükkân bazında borç">
          {debts_by_shop.length === 0 ? (
            <p className="sub">Bu hesabın hiçbir dükkânda borcu yok.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Dükkân</th>
                    <th>Sahibi</th>
                    <th className="num">Bakiye</th>
                  </tr>
                </thead>
                <tbody>
                  {debts_by_shop.map((shop) => (
                    <tr
                      key={shop.seller_id}
                      className="clickable"
                      onClick={() => navigate(`/sellers/${shop.seller_id}`)}
                    >
                      <td>{shop.shop_name ?? '—'}</td>
                      <td className="sub">{shop.display_name}</td>
                      <td className={`num ${shop.balance_minor > 0 ? 'neg' : ''}`}>
                        {money(shop.balance_minor)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="sub" style={{ marginTop: 12 }}>
            Aynı kişi her dükkânda ayrı bir defter kaydıdır; bakiye (satıcı, müşteri)
            çiftine göre hesaplanır.
          </p>
        </Section>

        <Section title="Yönetim">
          <AccountActions />
        </Section>

        <Entries entries={recent_entries} />
      </div>
    </>
  );
}
