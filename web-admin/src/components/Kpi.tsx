import type { CSSProperties, ReactNode } from 'react';

/*
 * The KPI card -- and the ONLY thing in this panel that carries .edge-trail.
 *
 * The phone's rule is that the focus card appears on six screens out of forty: an accent
 * that is everywhere stops being an accent. This is the same rule on the web, and keeping
 * the trail inside this one component is what enforces it -- there is no loose class to
 * sprinkle onto a table.
 */

export function KpiRow({ children }: { children: ReactNode }) {
  return <div className="kpi-row">{children}</div>;
}

export function Kpi({
  label,
  value,
  note,
  tone,
  index = 0,
}: {
  label: string;
  value: string;
  note?: string;
  tone?: 'pos' | 'neg';
  /** Position in the row. Only used to stagger the trails. */
  index?: number;
}) {
  // Quarters of the 5.5s period, so four cards in a row have their lights evenly spaced
  // instead of orbiting in lockstep. The delay is negated in CSS, not here.
  const style = { '--trail-delay': `${(index % 4) * 1.4}s` } as CSSProperties;

  return (
    <div className="card edge-trail" style={style}>
      <div className="kpi-label">{label}</div>
      <div className={`kpi-value ${tone ?? ''}`}>{value}</div>
      {note && <div className="kpi-note">{note}</div>}
    </div>
  );
}
