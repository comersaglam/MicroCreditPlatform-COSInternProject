import { api } from '../api/client';
import type { BuyerStats as Stats } from '../api/types';
import { ChartBox, Donut, MoneyBars, SERIES } from '../components/charts';
import { Kpi, KpiRow } from '../components/Kpi';
import { count, day, money, monthLabel } from '../components/format';
import { ErrorState, Loading, useApi } from '../components/ui';

export function BuyerStats() {
  const { data, error } = useApi(() => api.get<Stats>('/admin/stats/buyers'));

  if (error) return <ErrorState message={error} />;
  if (!data) return <Loading />;

  const claimRatio = data.claimed_customer_count /
    (data.claimed_customer_count + data.unclaimed_customer_count || 1);

  // The trailing month holds a few days, not thirty; charting it draws a cliff that reads
  // as a collapse. Dropped here and named in the note instead.
  const whole = data.monthly.filter((point) => !point.partial);
  const partial = data.monthly.find((point) => point.partial);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Alıcı istatistikleri</h1>
          <p className="sub">
            Aynı defterler, alıcı tarafından. Veri {day(data.data_through)} tarihine kadar.
          </p>
        </div>
      </div>

      <KpiRow>
        <Kpi index={0} label="Toplam borç" value={money(data.total_debt_minor)} tone="neg" />
        <Kpi index={1} label="Hesap" value={count(data.buyer_count)} />
        <Kpi
          index={2}
          label="Uygulamalı müşteri"
          value={`%${(claimRatio * 100).toFixed(0)}`}
          note={`${count(data.claimed_customer_count)} kayıtlı · ${count(data.unclaimed_customer_count)} uygulamasız`}
        />
        <Kpi
          index={3}
          label="Ortalama borç"
          value={money(data.buyer_count ? Math.round(data.total_debt_minor / data.buyer_count) : 0)}
        />
      </KpiRow>

      <div className="grid-2">
        <ChartBox
          title="Ne alınıyor"
          note="İşlem satırlarının kendi açıklamalarından gruplandı — ödemeler dışarıda."
        >
          <Donut
            data={data.by_category.map((row) => ({ label: row.label, value: row.amount_minor }))}
          />
        </ChartBox>

        <ChartBox
          title="Borç dağılımı"
          note="Yatay eksen borç aralığı (TL), dikey eksen kişi sayısı. Tek bir ortalamanın sakladığı şekil bu."
        >
          <MoneyBars
            data={data.debt_bands.map((band) => ({ label: band.label, value: band.amount_minor }))}
            unit="count"
          />
        </ChartBox>

        <ChartBox
          title="Aylık harcama"
          note={
            partial
              ? `${monthLabel(partial.month)} ayı tamamlanmadığı için grafiğe alınmadı.`
              : undefined
          }
        >
          <MoneyBars
            data={whole.map((point) => ({
              label: monthLabel(point.month),
              value: point.debt_minor,
            }))}
          />
        </ChartBox>

        <ChartBox
          title="En borçlu hesaplar"
          note="Bir kişinin borcu, bulunduğu her dükkândaki defter kaydının toplamıdır."
        >
          <MoneyBars
            data={data.top_debtors.slice(0, 8).map((row) => ({
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
