import { useState } from 'react';
import { Box, Chip, Tooltip, Typography } from '@mui/material';
import { ImageNotSupportedOutlined } from '@mui/icons-material';
import type { MatchLevel, Origin, OppStatus, Provenance, SourceKind, SupplierStage } from '@/api/sourcing';

export const pretty = (s?: string | null) => (s ? s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : '—');
export const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '—');
export const day = (iso?: string | null) => (iso ? iso.slice(0, 10) : '—');
export const inr = (v?: string | number | null) => (v === null || v === undefined || v === '' ? '—' : `₹${Number(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);

type Tone = 'default' | 'info' | 'success' | 'warning' | 'error' | 'primary';

const STATUS_COLOR: Record<OppStatus, Tone> = {
  DISCOVERED: 'default', UNDER_REVIEW: 'info', SHORTLISTED: 'primary', APPROVED_OPPORTUNITY: 'success', SUPPLIER_RESEARCH: 'info', QUOTE_COMPARISON: 'info', APPROVED_FOR_ONBOARDING: 'success',
  REJECTED: 'error', ON_HOLD: 'warning', NEEDS_MORE_EVIDENCE: 'warning',
};
const STATUS_LABEL: Partial<Record<OppStatus, string>> = { APPROVED_OPPORTUNITY: 'Approved as a business opportunity', APPROVED_FOR_ONBOARDING: 'Approved for sourcing preparation' };
export const StatusChip = ({ status }: { status: OppStatus }) => <Chip size="small" color={STATUS_COLOR[status] ?? 'default'} label={STATUS_LABEL[status] ?? pretty(status)} data-testid="opp-status" />;

const ORIGIN: Record<Origin, { label: string; color: 'default' | 'warning' | 'info' | 'success'; tip: string }> = {
  DEMO: { label: 'DEMO DATA', color: 'warning', tip: 'Labelled demonstration data: not real research and not a real quote.' },
  DEMAND_SIGNAL: { label: 'Customer searches', color: 'info', tip: 'Derived from searches that found nothing. Customer interest, not proven purchase intent.' },
  LIVE_RESEARCH: { label: 'Configured source', color: 'success', tip: 'Found by a configured, permitted research source.' },
  MANUAL: { label: 'Entered by a buyer', color: 'default', tip: 'Entered manually by a buyer.' },
};
export const OriginChip = ({ origin }: { origin: Origin }) => (
  <Tooltip title={ORIGIN[origin].tip}><Chip size="small" variant={origin === 'DEMO' ? 'filled' : 'outlined'} color={ORIGIN[origin].color} label={ORIGIN[origin].label} data-testid="origin-chip" /></Tooltip>
);

/** Where a record came from. Demonstration and test fixtures are loud; an unknown origin says so. Only the first three count towards live figures. */
const PROV: Record<Provenance, { label: string; color: 'default' | 'warning' | 'info' | 'success' | 'error'; tip: string; live: boolean }> = {
  LIVE_EXTERNAL_SOURCE: { label: 'Live source', color: 'success', tip: 'Retrieved from a configured, permitted external source. The source page is linked as evidence.', live: true },
  BUYER_ENTERED: { label: 'Buyer entered', color: 'default', tip: 'Entered by a buyer.', live: true },
  CUSTOMER_DEMAND_SIGNAL: { label: 'Customer demand', color: 'info', tip: 'Derived from customer searches. Interest only; not a product.', live: true },
  DEMO_FIXTURE: { label: 'DEMO DATA', color: 'warning', tip: 'Demonstration data. Not real research. Excluded from live figures.', live: false },
  TEST_FIXTURE: { label: 'TEST DATA', color: 'error', tip: 'Created by an automated test. Not real. Excluded from live figures.', live: false },
  UNKNOWN_PROVENANCE: { label: 'Unknown origin', color: 'warning', tip: 'No reliable origin is recorded. Excluded from live figures until a buyer classifies it.', live: false },
};
export const isLive = (p?: Provenance | null) => !!p && PROV[p].live;
export const ProvenanceChip = ({ provenance }: { provenance?: Provenance | null }) => {
  if (!provenance) return null;
  const p = PROV[provenance];
  return <Tooltip title={p.tip}><Chip size="small" variant={p.live ? 'outlined' : 'filled'} color={p.color} label={p.label} data-testid="provenance-chip" data-provenance={provenance} /></Tooltip>;
};

const KIND: Record<SourceKind, { color: 'default' | 'warning' | 'success' | 'info'; tip: string }> = {
  PUBLIC_LISTING: { color: 'default', tip: 'A publicly listed price. Not a quotation: confirm with the supplier.' },
  QUOTE_ENTERED: { color: 'info', tip: 'Entered from an actual supplier quotation.' },
  VERIFIED: { color: 'success', tip: 'Entered and then verified by a buyer.' },
  AI_ESTIMATE: { color: 'warning', tip: 'An AI estimate. Not a supplier figure.' },
};
export const KindChip = ({ kind, label }: { kind: SourceKind; label: string }) => <Tooltip title={KIND[kind].tip}><Chip size="small" color={KIND[kind].color} variant="outlined" label={label} data-testid="quote-kind" /></Tooltip>;

export const RiskChip = ({ level }: { level?: string | null }) => (
  <Chip size="small" variant="outlined" color={level === 'LOW' ? 'success' : level === 'MEDIUM' ? 'warning' : level === 'HIGH' ? 'error' : 'default'} label={`Supplier risk: ${pretty(level ?? 'UNKNOWN')}`} data-testid="risk-chip" />
);

export const MATCH_LABEL: Record<MatchLevel, { label: string; tip: string; color: 'success' | 'info' | 'warning' | 'default' }> = {
  EXACT_MODEL: { label: 'Exact model', tip: 'The product\'s own page declares this image for this exact model.', color: 'success' },
  LIKELY_MATCH: { label: 'Likely match', tip: 'Found on the product\'s page, but not declared as this exact model\'s image.', color: 'info' },
  SIMILAR_REFERENCE: { label: 'Reference only', tip: 'Shows a similar product. A reference, not this product.', color: 'warning' },
  UNVERIFIED: { label: 'Unverified', tip: 'Not verified to show this product.', color: 'default' },
};
export const MatchChip = ({ level }: { level?: MatchLevel | null }) => (level
  ? <Tooltip title={MATCH_LABEL[level].tip}><Chip size="small" variant="outlined" color={MATCH_LABEL[level].color} label={MATCH_LABEL[level].label} data-testid="image-match" data-level={level} /></Tooltip>
  : null);

/** The product picture, or an honest placeholder. A picture that fails to load (hotlink blocked, removed) falls back to the placeholder instead of a broken icon. */
export const ProductImage = ({ url, name, size = 160, level }: { url?: string | null; name: string; size?: number; level?: MatchLevel | null }) => {
  const [failed, setFailed] = useState(false);
  const show = !!url && !failed;
  return (
    <Box sx={{ width: '100%', height: size, bgcolor: 'action.hover', borderRadius: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', position: 'relative' }}
      data-testid={show ? 'product-image' : 'image-placeholder'}>
      {show
        ? <Box component="img" src={url} alt={level === 'EXACT_MODEL' ? name : `Reference image for ${name}`} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} sx={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
        : <Box sx={{ textAlign: 'center', color: 'text.secondary', px: 1 }}><ImageNotSupportedOutlined fontSize="large" /><Typography variant="caption" display="block">{url && failed ? 'Image could not be loaded' : 'No verified image yet'}</Typography></Box>}
    </Box>
  );
};

export const STAGES: SupplierStage[] = ['DISCOVERED', 'WEBSITE_CHECKED', 'IDENTITY_VERIFIED', 'BRAND_AUTHORISATION_VERIFIED'];
const STAGE_LABEL: Record<SupplierStage, string> = { DISCOVERED: 'Discovered (not verified)', WEBSITE_CHECKED: 'Website checked', IDENTITY_VERIFIED: 'Identity verified', BRAND_AUTHORISATION_VERIFIED: 'Brand authorisation verified' };
export const stageLabel = (s: SupplierStage) => STAGE_LABEL[s] ?? pretty(s);
export const StageChip = ({ stage }: { stage: SupplierStage }) => (
  <Chip size="small" variant="outlined" color={stage === 'DISCOVERED' ? 'default' : stage === 'BRAND_AUTHORISATION_VERIFIED' ? 'success' : 'info'} label={stageLabel(stage)} data-testid="stage-chip" />
);
