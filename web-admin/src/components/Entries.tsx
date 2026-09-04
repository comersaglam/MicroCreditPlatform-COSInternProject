import type { Entry } from '../api/types';
import { day, money } from './format';
import { MockBadge, Section } from './ui';

const TYPE_LABEL: Record<Entry['type'], string> = {
  DEBT: 'Veresiye',
  PAYMENT: 'Ödeme',
  INDEXATION: 'Enflasyon farkı',
};

/**
 * A ledger table, plus the one control in this panel that is disabled on purpose.
 *
 * "Ödemeyi düzelt" is greyed out because the architecture says no: `transactions` is
 * append-only at the database level (migration 0001's trigger), so a correction is a new
 * row in the opposite direction, not an edit. That flow was out of scope here
 * (deferred.md §L.2) -- and rather than a 501 endpoint nobody calls, the reason is written
 * where someone reads it.
 */
export function Entries({ entries }: { entries: Entry[] }) {
  return (
    <Section
      title="Son hareketler"
      aside={
        <div className="row">
          <button disabled title="Ledger append-only — düzeltme ters kayıtla yapılır">
            Ödemeyi düzelt
          </button>
          <MockBadge>append-only</MockBadge>
        </div>
      }
    >
      {entries.length === 0 ? (
        <p className="sub">Bu hesapta henüz hareket yok.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Tarih</th>
                <th>Karşı taraf</th>
                <th>Tür</th>
                <th>Açıklama</th>
                <th className="num">Tutar</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.transaction_id}>
                  <td>{day(entry.created_at)}</td>
                  <td>{entry.counterparty}</td>
                  <td>{TYPE_LABEL[entry.type]}</td>
                  <td className="sub">{entry.description ?? '—'}</td>
                  <td className={`num ${entry.type === 'PAYMENT' ? 'pos' : 'neg'}`}>
                    {entry.type === 'PAYMENT' ? '−' : '+'}
                    {money(entry.amount_minor)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="sub" style={{ marginTop: 12 }}>
        Enflasyon farkı satırları sunucunun yazdığı ayrı kayıtlardır — bakiyenin parçası,
        gösterge değil.
      </p>
    </Section>
  );
}
