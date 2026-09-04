/*
 * Kuruş in, Turkish money out. The only place in the panel that divides by 100.
 */

const MONEY = new Intl.NumberFormat('tr-TR', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const COMPACT = new Intl.NumberFormat('tr-TR', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

export function money(minor: number): string {
  return `${MONEY.format(minor / 100)} TL`;
}

/** For axis ticks, where "278.802,48 TL" would collide with its neighbour. */
export function moneyShort(minor: number): string {
  return COMPACT.format(minor / 100);
}

export function count(value: number): string {
  return new Intl.NumberFormat('tr-TR').format(value);
}

export function percent(ratio: number): string {
  return `%${(ratio * 100).toFixed(1)}`;
}

export function day(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('tr-TR', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
}

/** "2026-03" -> "Mar 26", for a twelve-point axis that has to stay readable. */
export function monthLabel(month: string): string {
  const [year, index] = month.split('-');
  const names = [
    'Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz',
    'Tem', 'Ağu', 'Eyl', 'Eki', 'Kas', 'Ara',
  ];
  return `${names[Number(index) - 1]} ${year.slice(2)}`;
}
