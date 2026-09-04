import { ChartBox, Donut, MoneyBars, SERIES } from '../components/charts';
import { Kpi, KpiRow } from '../components/Kpi';
import { MockBadge } from '../components/ui';

/*
 * ⚠️ EVERY NUMBER ON THIS PAGE IS INVENTED. There is no /admin/traffic endpoint and
 * nothing here reaches the database.
 *
 * The reason is two layers deep. audit_log is never written in production -- the request
 * middleware was deliberately not built (deferred.md §L.1) -- so the only rows that exist
 * are the ~3500 the demo seed generated. This tab goes one step further and does not read
 * even those (§L.21): the tab's job in the demo is to show the SHAPE of an operations
 * view, and wiring a chart to seeded rows would have dressed invented data as measurement.
 *
 * Drawn from the same distribution the seed uses, so the shape is at least the shape we
 * would expect: busier on Saturdays, quiet on Sundays, a midday and an evening peak, reads
 * dominating writes, a thin scatter of 4xx.
 */

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21];
const DAYS = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'];

const DAY_WEIGHT = [1.0, 0.95, 1.0, 1.05, 1.15, 1.55, 0.45];
const HOUR_WEIGHT = [0.3, 0.7, 0.8, 0.9, 1.6, 1.2, 0.6, 0.5, 0.6, 1.0, 1.7, 1.1, 0.8, 0.4];

const ENDPOINTS = [
  { label: 'GET /customers', value: 1045 },
  { label: 'GET /transactions', value: 748 },
  { label: 'GET /me/debts', value: 602 },
  { label: 'GET /approvals', value: 523 },
  { label: 'POST /transactions', value: 231 },
  { label: 'POST /approvals', value: 138 },
  { label: 'GET /pgw-jobs', value: 102 },
  { label: 'POST /auth/otp/verify', value: 74 },
];

const STATUS = [
  { label: '200', value: 2731 },
  { label: '201', value: 419 },
  { label: '401', value: 175 },
  { label: '403', value: 105 },
  { label: '404', value: 69 },
];

const DAILY = Array.from({ length: 30 }, (_, index) => ({
  label: String(index + 1),
  value: Math.round(38 + 22 * Math.sin(index / 3) + (index % 7 === 5 ? 26 : 0)),
}));

function heatColour(intensity: number): string {
  // One hue, opacity carrying the value: a heatmap that changes hue invents categories
  // where there is only "more" and "less".
  return `hsl(222 100% 68% / ${(0.06 + intensity * 0.72).toFixed(3)})`;
}

export function Traffic() {
  const cells = DAYS.map((_, dayIndex) =>
    HOURS.map((__, hourIndex) => DAY_WEIGHT[dayIndex] * HOUR_WEIGHT[hourIndex]),
  );
  const peak = Math.max(...cells.flat());

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Trafik</h1>
          <p className="sub">İstek hacmi, dağılım ve hata oranı.</p>
        </div>
      </div>



      <KpiRow>
        <Kpi index={0} label="Günlük ortalama istek" value="48" note="son 30 gün" />
        <Kpi index={1} label="Tepe saat" value="18:00" />
        <Kpi index={2} label="Hata oranı" value="%9,8" />
        <Kpi index={3} label="En yoğun gün" value="Cumartesi" />
      </KpiRow>

      <div className="notice">
        <span>⚠️</span>
        <div>
          <strong>Bu sekmedeki rakamların tamamı örnektir.</strong> Sistem canlıya
          alınmadığı için gerçek istek kaydı yok: <code>audit_log</code> tablosuna canlı
          yazan bir middleware bilinçli olarak yazılmadı. Panelin diğer sekmeleri gerçek veritabanından
          besleniyor — burası operasyon görünümünün <em>şeklini</em> gösteriyor.
        </div>
      </div>
      <div className="grid-2">
        <ChartBox title="Günlük istek (30 gün)">
          <MoneyBars data={DAILY} unit="count" />
        </ChartBox>

        <ChartBox title="Uç noktalara göre dağılım">
          <Donut data={ENDPOINTS} />
        </ChartBox>

        <ChartBox title="Durum kodları">
          <MoneyBars data={STATUS} unit="count" color={SERIES.muted} />
        </ChartBox>

        <div className="panel" style={{ padding: 20 }}>
          <h2>Saat × gün yoğunluğu</h2>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ borderCollapse: 'separate', borderSpacing: 3 }}>
              <tbody>
                {DAYS.map((day, dayIndex) => (
                  <tr key={day}>
                    <td
                      style={{
                        border: 'none',
                        padding: '0 8px 0 0',
                        color: 'var(--muted)',
                        fontSize: 11,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {day}
                    </td>
                    {HOURS.map((hour, hourIndex) => (
                      <td
                        key={hour}
                        title={`${day} ${hour}:00`}
                        style={{
                          border: 'none',
                          padding: 0,
                          width: 18,
                          height: 18,
                          borderRadius: 3,
                          background: heatColour(cells[dayIndex][hourIndex] / peak),
                        }}
                      />
                    ))}
                  </tr>
                ))}
                <tr>
                  <td style={{ border: 'none' }} />
                  {HOURS.map((hour) => (
                    <td
                      key={hour}
                      style={{
                        border: 'none',
                        padding: '4px 0 0',
                        color: 'var(--muted)',
                        fontSize: 9,
                        textAlign: 'center',
                      }}
                    >
                      {hour % 3 === 0 ? hour : ''}
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
          <p className="sub" style={{ marginTop: 14 }}>
            <MockBadge>örnek veri</MockBadge> Öğle ve akşam tepeleri, cumartesi yoğunluğu.
          </p>
        </div>
      </div>
    </>
  );
}
