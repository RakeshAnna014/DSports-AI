import type { ReactNode } from 'react';
import { Alert, AlertTitle, Box, Button, Chip, Paper, Skeleton, Stack, Tab, Tabs, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { HomeOutlined } from '@mui/icons-material';
import { colors, gradients, shadows } from '@/design/tokens';
import { PROVENANCE, tone as toneColor, type ProvenanceKind, type Tone } from './semantics';

/** Where a figure came from. Always rendered next to the number it describes, so synthetic or estimated values are never mistaken for recorded ones. */
export const ProvenanceChip = ({ kind, label, hint, dark }: { kind: ProvenanceKind; label?: string; hint?: string; dark?: boolean }) => {
  const p = PROVENANCE[kind];
  return (
    <Tooltip title={hint ?? p.hint} arrow>
      <Chip size="small" label={label ?? p.label} data-testid={`prov-${kind}`} data-provenance={kind}
        sx={{ height: 20, fontSize: 11, fontWeight: 800, letterSpacing: 0.2, color: dark ? '#fff' : p.color, bgcolor: dark ? 'rgba(255,255,255,0.16)' : p.bg, border: `1px solid ${dark ? 'rgba(255,255,255,0.3)' : p.color}33` }} />
    </Tooltip>
  );
};

/** Evidence strength as three segments plus the reason; never a bare colour. */
export const ConfidenceMeter = ({ level, reason }: { level: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE' | null | undefined; reason?: string }) => {
  const n = level === 'HIGH' ? 3 : level === 'MEDIUM' ? 2 : level === 'LOW' ? 1 : 0;
  const c = n === 3 ? toneColor.good : n === 2 ? toneColor.info : n === 1 ? toneColor.warn : '#98A2B3';
  return (
    <Tooltip title={reason ?? (level ? `${level.toLowerCase()} confidence` : 'no confidence rating')} arrow>
      <Stack direction="row" spacing={0.5} alignItems="center" data-testid="confidence" data-level={level ?? 'NONE'}>
        <Stack direction="row" spacing={0.25}>{[1, 2, 3].map((i) => <Box key={i} sx={{ width: 10, height: 6, borderRadius: 1, bgcolor: i <= n ? c : '#E4E7EC' }} />)}</Stack>
        <Typography variant="caption" fontWeight={700} color="text.secondary">{level ? level.charAt(0) + level.slice(1).toLowerCase() : 'None'}</Typography>
      </Stack>
    </Tooltip>
  );
};

/** The common card: title, optional subtitle, provenance and an action, then the content. */
export const SectionCard = ({ title, subtitle, action, provenance, children, testId, sx, dense }:
  { title?: ReactNode; subtitle?: ReactNode; action?: ReactNode; provenance?: ProvenanceKind | ProvenanceKind[]; children: ReactNode; testId?: string; sx?: object; dense?: boolean }) => (
  <Paper elevation={0} data-testid={testId} sx={{ p: dense ? 1.5 : { xs: 1.75, md: 2.25 }, borderRadius: 3, border: `1px solid ${colors.line}`, boxShadow: shadows.card, bgcolor: '#fff', minWidth: 0, ...sx }}>
    {(title || action) && (
      <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={1} sx={{ mb: 1.25 }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap>
            {title && <Typography variant="subtitle1" fontWeight={800} lineHeight={1.25}>{title}</Typography>}
            {provenance && (Array.isArray(provenance) ? provenance : [provenance]).map((k) => <ProvenanceChip key={k} kind={k} />)}
          </Stack>
          {subtitle && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>{subtitle}</Typography>}
        </Box>
        {action}
      </Stack>
    )}
    {children}
  </Paper>
);

/** A headline number with its change, provenance and (optionally) a drill-down. A value that cannot be computed shows why. */
export const KpiCard = ({ label, value, sub, delta, deltaTone, provenance, accent, onClick, testId, unavailable }:
  { label: string; value: ReactNode; sub?: ReactNode; delta?: string; deltaTone?: Tone; provenance?: ProvenanceKind; accent?: string; onClick?: () => void; testId?: string; unavailable?: string }) => (
  <Paper elevation={0} onClick={onClick} onKeyDown={onClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } } : undefined}
    role={onClick ? 'button' : undefined} tabIndex={onClick ? 0 : undefined} data-testid={testId}
    sx={{ p: 1.75, pt: 2.1, borderRadius: 3, border: `1px solid ${colors.line}`, boxShadow: shadows.card, cursor: onClick ? 'pointer' : 'default', position: 'relative', overflow: 'hidden', height: '100%', minWidth: 0,
      '&::before': { content: '""', position: 'absolute', left: 0, right: 0, top: 0, height: 4, bgcolor: accent ?? colors.green },
      '&:hover': onClick ? { boxShadow: shadows.cardHover } : undefined, '&:focus-visible': { outline: `2px solid ${colors.green}`, outlineOffset: 2 } }}>
    <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={0.5}>
      <Typography variant="caption" color="text.secondary" fontWeight={800} sx={{ textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</Typography>
      {provenance && <ProvenanceChip kind={provenance} />}
    </Stack>
    {unavailable
      ? <Typography variant="h5" fontWeight={900} color="text.disabled" sx={{ mt: 0.5 }}>—</Typography>
      : <Typography variant="h4" fontWeight={900} lineHeight={1.15} sx={{ mt: 0.5, fontSize: { xs: '1.5rem', md: '1.9rem' } }}>{value}</Typography>}
    {delta && !unavailable && <Typography variant="caption" fontWeight={800} sx={{ color: toneColor[deltaTone ?? 'neutral'] }}>{delta}</Typography>}
    {(unavailable ?? sub) && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{unavailable ?? sub}</Typography>}
  </Paper>
);

export const KpiGrid = ({ children, min = 200 }: { children: ReactNode; min?: number }) => (
  <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(auto-fill, minmax(${min}px, 1fr))`, gap: 1.5 }}>{children}</Box>
);

export const LoadingBlock = ({ rows = 3, testId = 'loading' }: { rows?: number; testId?: string }) => (
  <Stack spacing={1.25} data-testid={testId} aria-busy="true" aria-label="Loading">
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 1.5 }}>{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} variant="rounded" height={92} />)}</Box>
    {Array.from({ length: rows }).map((_, i) => <Skeleton key={i} variant="rounded" height={i === 0 ? 220 : 56} />)}
  </Stack>
);

export const EmptyState = ({ title, body, action, testId = 'empty' }: { title: string; body?: ReactNode; action?: ReactNode; testId?: string }) => (
  <Box data-testid={testId} sx={{ textAlign: 'center', py: 4, px: 2, border: `1px dashed ${colors.line}`, borderRadius: 3, bgcolor: '#FAFBFD' }}>
    <Typography fontWeight={800}>{title}</Typography>
    {body && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: 560, mx: 'auto' }}>{body}</Typography>}
    {action && <Box sx={{ mt: 1.5 }}>{action}</Box>}
  </Box>
);

/** A figure that cannot be computed from the current data: says why, and what would make it computable. */
export const DataUnavailable = ({ what, why, needs, testId = 'unavailable' }: { what: string; why: string; needs?: string; testId?: string }) => (
  <Alert severity="info" icon={false} data-testid={testId} sx={{ borderRadius: 2.5, alignItems: 'center' }}>
    <AlertTitle sx={{ m: 0, fontWeight: 800 }}>{what} is not available</AlertTitle>
    <Typography variant="body2">{why}{needs ? <> <b>To enable it:</b> {needs}</> : null}</Typography>
  </Alert>
);

export const ErrorPanel = ({ message, onRetry, testId = 'error' }: { message: string; onRetry?: () => void; testId?: string }) => (
  <Alert severity="warning" data-testid={testId} sx={{ borderRadius: 2.5 }} action={onRetry ? <Button color="inherit" size="small" onClick={onRetry}>Try again</Button> : undefined}>{message}</Alert>
);

export interface HeroTab { key: string; label: string; testId?: string; badge?: number | string }

/** The shared header of the internal screens: dark brand band, title, scope badges, actions, tabs. Planning, Inventory and Product Intelligence all use it. */
export const PageHero = ({ overline, title, subtitle, badges, actions, tabs, tab, onTab, testId, tabsTestId, homeTestId, titleTestId }:
  { overline: string; title: string; subtitle?: ReactNode; badges?: ReactNode; actions?: ReactNode; tabs: HeroTab[]; tab: string; onTab: (key: string) => void; testId?: string; tabsTestId?: string; homeTestId?: string; titleTestId?: string }) => (
  <Box component="header" data-testid={testId} sx={{ background: gradients.hero, color: '#fff', px: { xs: 2, md: 4 }, pt: 2, pb: 0 }}>
    <Stack direction="row" alignItems="center" spacing={1.5} flexWrap="wrap" useFlexGap sx={{ pb: 1 }}>
      <Box sx={{ flex: 1, minWidth: 240 }}>
        <Typography variant="overline" sx={{ color: colors.lime, fontWeight: 800, letterSpacing: 1.5 }}>{overline}</Typography>
        <Typography variant="h5" fontWeight={900} data-testid={titleTestId}>{title}</Typography>
        {subtitle && <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.74)', maxWidth: 880, mt: 0.25 }}>{subtitle}</Typography>}
      </Box>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        {badges}
        {actions}
        <Button size="small" color="inherit" component={RouterLink} to="/" startIcon={<HomeOutlined />} aria-label="Home" data-testid={homeTestId}>Storefront</Button>
      </Stack>
    </Stack>
    <Tabs value={tab} onChange={(_, v: string) => onTab(v)} variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile textColor="inherit" data-testid={tabsTestId}
      TabIndicatorProps={{ sx: { bgcolor: colors.lime, height: 3 } }}>
      {tabs.map((t) => (
        <Tab key={t.key} value={t.key} data-testid={t.testId} sx={{ color: '#fff', opacity: tab === t.key ? 1 : 0.72, fontWeight: 700, textTransform: 'none', minHeight: 44 }}
          label={t.badge !== undefined && t.badge !== 0 ? <Stack direction="row" spacing={0.75} alignItems="center"><span>{t.label}</span><Chip size="small" label={t.badge} sx={{ height: 18, fontSize: 11, fontWeight: 800, bgcolor: colors.orange, color: '#fff' }} /></Stack> : t.label} />
      ))}
    </Tabs>
  </Box>
);

/** The workflow a screen walks the user through; the current step is highlighted, finished ones are ticked. */
export const WorkflowSteps = ({ steps, current, testId = 'workflow' }: { steps: string[]; current: number; testId?: string }) => (
  <Stack direction="row" alignItems="center" spacing={0.5} flexWrap="wrap" useFlexGap data-testid={testId} aria-label="Workflow">
    {steps.map((s, i) => (
      <Stack key={s} direction="row" alignItems="center" spacing={0.5}>
        <Chip size="small" label={`${i < current ? '✓ ' : ''}${s}`} data-state={i < current ? 'done' : i === current ? 'current' : 'todo'}
          sx={{ fontWeight: 800, bgcolor: i === current ? colors.green : i < current ? colors.greenSoft : '#F2F4F7', color: i === current ? '#fff' : i < current ? colors.greenDark : '#667085' }} />
        {i < steps.length - 1 && <Typography variant="caption" color="text.disabled">›</Typography>}
      </Stack>
    ))}
  </Stack>
);
