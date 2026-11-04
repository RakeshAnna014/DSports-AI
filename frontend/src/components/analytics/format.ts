/** Number and date formatting shared by the analytics screens. A missing value is an em dash, never 0. */
const missing = (v: number | null | undefined): v is null | undefined => v === null || v === undefined || Number.isNaN(v);

export const num = (v: number | null | undefined, digits = 0) => (missing(v) ? '—' : v.toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits }));
export const signedNum = (v: number | null | undefined, digits = 0) => (missing(v) ? '—' : `${v > 0 ? '+' : ''}${num(v, digits)}`);
export const pct = (v: number | null | undefined, digits = 1) => (missing(v) ? '—' : `${num(v, digits)}%`);
export const signedPct = (v: number | null | undefined, digits = 1) => (missing(v) ? '—' : `${v > 0 ? '+' : ''}${num(v, digits)}%`);
export const rupees = (v: number | null | undefined) => (missing(v) ? '—' : `₹${Math.round(v).toLocaleString('en-IN')}`);
/** 12,400 → 12.4K ; 2,300,000 → 2.3M ; used on chart axes only. */
export const compact = (v: number) => {
  const a = Math.abs(v);
  if (a >= 1e7) return `${(v / 1e7).toFixed(1)}Cr`;
  if (a >= 1e5) return `${(v / 1e5).toFixed(1)}L`;
  if (a >= 1e3) return `${(v / 1e3).toFixed(a >= 1e4 ? 0 : 1)}K`;
  return a >= 10 ? `${Math.round(v)}` : `${Math.round(v * 10) / 10}`;
};
export const shortDay = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00`).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
export const isoDay = (iso?: string | null) => (iso ? iso.slice(0, 10) : '—');
export const titleCase = (s?: string | null) => (s ? s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : '—');
