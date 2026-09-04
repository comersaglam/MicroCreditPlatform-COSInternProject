import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { SellerDetail as Detail } from '../api/types';
import { AccountActions } from '../components/AccountActions';
import { Entries } from '../components/Entries';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, day, money } from '../components/format';
import { ErrorState, Loading, Section, useApi } from '../components/ui';

export function SellerDetail() {
  const { userId } = useParams();
  const { data, error } = useApi(
    () => api.get<Detail>(`/admin/sellers/${userId}`),
    [userId],
  );

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  const { seller, breakdown, customers, recent_entries } = data;
  const inflationShare = breakdown.principal_minor
    ? breakdown.indexation_minor / breakdown.principal_minor
    : 0;

  return (
    <>
      <div className="page-head">
        <div>
          <Link className="back" to="/sellers">
            ← Satıcılar
          </Link>
          <h1 style={{ marginTop: 8 }}>{seller.shop_name ?? seller.display_name}</h1>
          <p className="sub">
            {seller.display_name} · {seller.phone} · {day(seller.created_at)} tarihinden beri
          </p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Açık alacak" value={money(breakdown.outstanding_minor)} tone="neg" />
        <Kpi index={1} label="Anapara" value={money(breakdown.principal_minor)} />
        <Kpi
          index={2}
          label="Enflasyon farkı"
          value={money(breakdown.indexation_minor)}
          note={`anaparanın %${(inflationShare * 100).toFixed(1)}'i`}
        />
        <Kpi index={3} label="Tahsil edilen" value={money(breakdown.total_paid_minor)} tone="pos" />
      </KpiRow>

      <div className="stack">
        <Section
          title="Defter"
          aside={<span className="sub">{count(seller.customer_count)} müşteri · {count(seller.entry_count)} hareket</span>}
        >
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Müşteri</th>
                  <th>Telefon</th>
                  <th>Durum</th>
                  <th className="num">Bakiye</th>
                </tr>
              </thead>
              <tbody>
                {customers.map((customer) => (
                  <tr key={customer.customer_id}>
                    <td>{customer.display_name}</td>
                    <td className="money">{customer.phone}</td>
                    <td>
                      {/* CLAIMED means this person holds the app; UNCLAIMED is a name the
                          shopkeeper wrote down for someone who does not. */}
                      <span className="badge">
                        {customer.claim_status === 'CLAIMED' ? 'uygulamada' : 'uygulamasız'}
                      </span>
                    </td>
                    <td className={`num ${customer.balance_minor > 0 ? 'neg' : ''}`}>
                      {money(customer.balance_minor)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {breakdown.projected_3m_minor != null && (
            <p className="sub" style={{ marginTop: 14 }}>
              Bugün tahsil edilmezse üç ay sonra yaklaşık{' '}
              <strong className="money">{money(breakdown.projected_3m_minor)}</strong> —
              son on iki ayın ortalama TÜFE artışıyla.
            </p>
          )}
        </Section>

        <Section title="Yönetim">
          <AccountActions />
        </Section>

        <Entries entries={recent_entries} />
      </div>
    </>
  );
}
