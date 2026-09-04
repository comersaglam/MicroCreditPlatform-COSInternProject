import { api } from '../api/client';
import type { SellerStats as Stats } from '../api/types';
import { ChartBox, MoneyBars, MoneyLines, SERIES } from '../components/charts';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, day, money, monthLabel, percent } from '../components/format';
import { ErrorState, Loading, useApi } from '../components/ui';

export function SellerStats() {
  const { data, error } = useApi(() => api.get<Stats>('/admin/stats/sellers'));

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  // The month the data stops inside is dropped from every series. It holds a few days
  // rather than a month, so plotting it drew a vertical fall at the right edge of both
  // charts -- which reads as collections collapsing when in fact the data simply ends.
  const whole = data.monthly.filter((point) => !point.partial);
  const partial = data.monthly.find((point) => point.partial);

  const months = whole.map((point) => ({
    label: monthLabel(point.month),
    debt: point.debt_minor,
    payment: point.payment_minor,
  }));

  const partialNote = partial
    ? ` ${monthLabel(partial.month)} ayı tamamlanmadığı için grafiğe alınmadı.`
    : '';

  const inflationShare = data.breakdown.principal_minor
    ? data.breakdown.indexation_minor / data.breakdown.principal_minor
    : 0;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Satıcı istatistikleri</h1>
          <p className="sub">
            Platformun tamamı, satıcı tarafından. Veri {day(data.data_through)} tarihine
            kadar.
          </p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Toplam alacak" value={money(data.total_receivable_minor)} tone="neg" />
        <Kpi
          index={1}
          label="Enflasyon farkı"
          value={money(data.breakdown.indexation_minor)}
          note={`anaparanın %${(inflationShare * 100).toFixed(1)}'i`}
        />
        <Kpi index={2} label="Tahsilat oranı" value={percent(data.collection_rate)} tone="pos" note="verilen veresiyenin ödenen kısmı" />
        <Kpi index={3} label="Dükkân / müşteri" value={`${count(data.seller_count)} / ${count(data.customer_count)}`} />
      </KpiRow>

      <div className="grid-2">
        <ChartBox
          title="Aylık veresiye ve tahsilat"
          note={`Bayram öncesi ve okul dönemi tepeleri gerçek hareketlerden geliyor.${partialNote}`}
        >
          <MoneyLines
            data={months}
            lines={[
              { key: 'debt', name: 'Veresiye', color: SERIES.error },
              { key: 'payment', name: 'Tahsilat', color: SERIES.accent },
            ]}
          />
        </ChartBox>

        <ChartBox title="Dükkân bazında alacak">
          <MoneyBars
            data={data.top_sellers.map((row) => ({ label: row.label, value: row.amount_minor }))}
            horizontal
          />
        </ChartBox>

        <ChartBox
          title="Aylık enflasyon farkı"
          note="Her ay, o ay hâlâ açık olan bakiyelere işlenen TÜFE farkı. Ledger'da ayrı satırlar olarak duruyor."
        >
          <MoneyBars
            data={whole.map((point) => ({
              label: monthLabel(point.month),
              value: point.indexation_minor,
            }))}
            color={SERIES.error}
          />
        </ChartBox>

        <ChartBox
          title="En yüksek bakiyeli müşteriler"
          note="Sıralama bakiyeye göre — bir kredi skoru değil."
        >
          <MoneyBars
            data={data.riskiest_customers.map((row) => ({
              label: row.label,
              value: row.amount_minor,
            }))}
            horizontal
            color={SERIES.error}
          />
        </ChartBox>
      </div>
    </>
  );
}
