import { colors } from '@/design/tokens';

/** One colour language for every analytics screen: what a series IS (forecast, target, actual), whether a variance is good or bad, and where a figure came from. */
export const series = {
  model: '#5B6CFF',
  forecast: colors.navy600,
  target: colors.orange,
  actual: colors.green,
  baseline: '#98A2B3',
} as const;

export const tone = { good: '#12B76A', bad: '#D92D20', warn: '#F79009', info: '#2E90FA', neutral: '#667085' } as const;
export type Tone = keyof typeof tone;

/** Where a number came from. Persisted provenance is mapped onto these kinds by `provenanceOf`; a label alone never decides it. */
export type ProvenanceKind = 'LIVE' | 'DEMO' | 'IMPORTED' | 'FORECAST' | 'ESTIMATE' | 'QUOTE' | 'LISTING' | 'VERIFIED' | 'MANUAL' | 'UNAVAILABLE';

export const PROVENANCE: Record<ProvenanceKind, { label: string; color: string; bg: string; hint: string }> = {
  LIVE: { label: 'Live', color: '#067647', bg: '#ECFDF3', hint: 'Recorded DSports transactions (real online orders).' },
  DEMO: { label: 'Demonstration', color: '#B54708', bg: '#FFF4E5', hint: 'Synthetic demonstration data. Not real DSports stores, sales or stock, and never a validated prediction.' },
  IMPORTED: { label: 'Imported', color: '#175CD3', bg: '#EFF8FF', hint: 'Entered through a controlled, audited import.' },
  FORECAST: { label: 'Forecast', color: '#3538CD', bg: '#EEF4FF', hint: 'A statistical forecast: an estimate, not an observation.' },
  ESTIMATE: { label: 'AI estimate', color: '#C4320A', bg: '#FEF6EE', hint: 'An estimate or hypothesis, not a recorded fact.' },
  QUOTE: { label: 'Supplier quote', color: '#6941C6', bg: '#F4F3FF', hint: 'A price the supplier quoted to DSports.' },
  LISTING: { label: 'Public listing', color: '#0E7090', bg: '#ECFDFF', hint: 'A publicly listed price or product detail; not a quotation.' },
  VERIFIED: { label: 'Buyer-verified', color: '#067647', bg: '#ECFDF3', hint: 'Checked by a buyer.' },
  MANUAL: { label: 'Entered', color: '#344054', bg: '#F2F4F7', hint: 'Entered by a user.' },
  UNAVAILABLE: { label: 'Unavailable', color: '#475467', bg: '#F2F4F7', hint: 'No data supports this figure.' },
};

/** Planning data scopes and sourcing source kinds, mapped to a provenance kind. Unknown values become UNAVAILABLE rather than being guessed. */
export const provenanceOf = (value: string | null | undefined): ProvenanceKind => {
  switch ((value ?? '').toUpperCase()) {
    case 'DSPORTS_LIVE': case 'LIVE': case 'LIVE_ORDERS': return 'LIVE';
    case 'POC_SYNTHETIC': case 'POC_REFERENCE': case 'DEMO': case 'SYNTHETIC': return 'DEMO';
    case 'IMPORTED': return 'IMPORTED';
    case 'FORECAST': case 'MODEL': return 'FORECAST';
    case 'AI_ESTIMATE': case 'ESTIMATE': case 'ESTIMATED': return 'ESTIMATE';
    case 'ENTERED_QUOTATION': case 'SUPPLIER_QUOTE': case 'QUOTE': return 'QUOTE';
    case 'PUBLIC_LISTING': case 'LISTING': case 'LIVE_EXTERNAL_SOURCE': return 'LISTING';
    case 'BUYER_VERIFIED': case 'VERIFIED': return 'VERIFIED';
    case 'BUYER_ENTERED': case 'MANUAL': return 'MANUAL';
    default: return 'UNAVAILABLE';
  }
};

/** Positive variance (actual above forecast) is good for sales; for cost or shortage the caller inverts it. */
export const varianceTone = (v: number | null | undefined, higherIsBetter = true): Tone => (v === null || v === undefined || v === 0 ? 'neutral' : (v > 0) === higherIsBetter ? 'good' : 'bad');

export const riskColor: Record<string, string> = { HIGH: '#D92D20', MEDIUM: '#F79009', LOW: '#12B76A', NONE: '#98A2B3' };

/** Sequential scale for heatmaps: 0 → very light, 1 → deep green. */
export const heat = (t: number) => {
  const c = Math.max(0, Math.min(1, t));
  const a = [231, 248, 238];
  const b = [15, 122, 56];
  return `rgb(${a.map((v, i) => Math.round(v + (b[i] - v) * c)).join(',')})`;
};
/** Diverging scale for variances: negative red, positive green, 0 neutral. `t` is in [-1, 1]. */
export const divergingHeat = (t: number) => {
  const c = Math.max(-1, Math.min(1, t));
  const [r, g, b] = c >= 0 ? [18 + (255 - 18) * (1 - c), 183 + (255 - 183) * (1 - c) * 0.9, 106 + (255 - 106) * (1 - c)] : [217 + (255 - 217) * (1 + c), 45 + (255 - 45) * (1 + c), 32 + (255 - 32) * (1 + c)];
  return `rgb(${Math.round(r)},${Math.round(g)},${Math.round(b)})`;
};
