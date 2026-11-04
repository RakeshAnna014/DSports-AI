import { useMemo, useState, type ReactNode } from 'react';
import { Box, Drawer, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { CloseRounded } from '@mui/icons-material';
import { colors } from '@/design/tokens';
import { compact, num } from './format';
import { divergingHeat, heat, tone } from './semantics';

export interface ChartSeries { key: string; name: string; color: string; dashed?: boolean; area?: boolean }
export interface ChartPoint { x: string; values: Record<string, number | null | undefined>; band?: Record<string, [number, number] | undefined> }

const W = 860;
const PAD = { l: 52, r: 14, t: 14, b: 30 };

/**
 * Multi-series time chart. A null/undefined value is a GAP (never drawn as zero), an optional [lo, hi] band is drawn behind its series, and a hover crosshair lists the exact values.
 * Series are drawn from persisted data only; the legend doubles as the colour key.
 */
export const TimeSeriesChart = ({ points, series, height = 260, unit = 'units', marker, testId = 'ts-chart', label = 'Time series', xLabel = (x: string) => x.slice(5) }:
  { points: ChartPoint[]; series: ChartSeries[]; height?: number; unit?: string; marker?: { x: string; label: string }; testId?: string; label?: string; xLabel?: (x: string) => string }) => {
  const [hover, setHover] = useState<number | null>(null);
  const H = height;
  const g = useMemo(() => {
    const all = points.flatMap((p) => [...series.map((s) => p.values[s.key]), ...Object.values(p.band ?? {}).flatMap((b) => (b ? [b[0], b[1]] : []))]).filter((v): v is number => typeof v === 'number');
    const max = Math.max(1, ...all) * 1.08;
    const x = (i: number) => PAD.l + (points.length < 2 ? 0 : (i / (points.length - 1)) * (W - PAD.l - PAD.r));
    const y = (v: number) => PAD.t + (1 - v / max) * (H - PAD.t - PAD.b);
    const paths = series.map((s) => {
      const segs: string[] = [];
      let cur: string[] = [];
      const flush = () => { if (cur.length) segs.push(cur.join(' ')); cur = []; };
      points.forEach((p, i) => { const v = p.values[s.key]; if (typeof v !== 'number') flush(); else cur.push(`${cur.length ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`); });
      flush();
      const areas = s.area ? segs.map((d) => `${d} L${d.split(' ').pop()!.split(',')[0].slice(1)},${y(0)} L${d.split(' ')[0].slice(1).split(',')[0]},${y(0)} Z`) : [];
      return { s, segs, areas };
    });
    const bands = series.flatMap((s) => {
      const run: { i: number; lo: number; hi: number }[][] = [];
      let cur: { i: number; lo: number; hi: number }[] = [];
      points.forEach((p, i) => { const b = p.band?.[s.key]; if (b) cur.push({ i, lo: b[0], hi: b[1] }); else if (cur.length) { run.push(cur); cur = []; } });
      if (cur.length) run.push(cur);
      return run.map((r) => ({ s, d: `${r.map((c, k) => `${k ? 'L' : 'M'}${x(c.i).toFixed(1)},${y(c.hi).toFixed(1)}`).join(' ')} ${[...r].reverse().map((c) => `L${x(c.i).toFixed(1)},${y(c.lo).toFixed(1)}`).join(' ')} Z` }));
    });
    return { max, x, y, paths, bands };
  }, [points, series, H]);
  if (points.length < 2 || !series.some((s) => points.some((p) => typeof p.values[s.key] === 'number')))
    return <Typography variant="body2" color="text.secondary" data-testid={`${testId}-empty`}>Not enough points to draw this chart.</Typography>;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => g.max * t);
  const every = Math.max(1, Math.ceil(points.length / 9));
  const markerIx = marker ? points.findIndex((p) => p.x === marker.x) : -1;
  const at = hover === null ? null : points[hover];
  return (
    <Box data-testid={testId} sx={{ width: '100%', minWidth: 0 }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={label} style={{ display: 'block' }} onMouseLeave={() => setHover(null)}
        onMouseMove={(e) => { const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect(); const px = ((e.clientX - r.left) / r.width) * W; setHover(Math.max(0, Math.min(points.length - 1, Math.round(((px - PAD.l) / (W - PAD.l - PAD.r)) * (points.length - 1))))); }}>
        {ticks.map((t) => <g key={t}><line x1={PAD.l} x2={W - PAD.r} y1={g.y(t)} y2={g.y(t)} stroke={colors.line} /><text x={PAD.l - 6} y={g.y(t) + 4} fontSize="12" textAnchor="end" fill={colors.muted}>{compact(t)}</text></g>)}
        {g.bands.map((b, i) => <path key={i} d={b.d} fill={b.s.color} opacity={0.13} data-testid="ts-band" />)}
        {g.paths.map(({ s, segs, areas }) => <g key={s.key}>
          {areas.map((d, i) => <path key={`a${i}`} d={d} fill={s.color} opacity={0.1} />)}
          {segs.map((d, i) => <path key={i} d={d} fill="none" stroke={s.color} strokeWidth={2.2} strokeDasharray={s.dashed ? '5 4' : undefined} strokeLinejoin="round" strokeLinecap="round" data-testid={`ts-line-${s.key}`} />)}
        </g>)}
        {markerIx >= 0 && <g><line x1={g.x(markerIx)} x2={g.x(markerIx)} y1={PAD.t} y2={H - PAD.b} stroke={colors.orange} strokeDasharray="4 4" /><text x={g.x(markerIx) + 5} y={PAD.t + 10} fontSize="12" fill={colors.orange} fontWeight={700}>{marker!.label}</text></g>}
        {points.map((p, i) => (i % every === 0 ? <text key={p.x} x={g.x(i)} y={H - 8} fontSize="12" textAnchor="middle" fill={colors.muted}>{xLabel(p.x)}</text> : null))}
        {hover !== null && <line x1={g.x(hover)} x2={g.x(hover)} y1={PAD.t} y2={H - PAD.b} stroke={colors.navy700} strokeOpacity={0.35} />}
        {hover !== null && series.map((s) => { const v = points[hover].values[s.key]; return typeof v === 'number' ? <circle key={s.key} cx={g.x(hover)} cy={g.y(v)} r={4} fill="#fff" stroke={s.color} strokeWidth={2} /> : null; })}
      </svg>
      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
        {series.map((s) => <Stack key={s.key} direction="row" spacing={0.6} alignItems="center"><Box sx={{ width: 16, height: 0, borderTop: `3px ${s.dashed ? 'dashed' : 'solid'} ${s.color}` }} /><Typography variant="caption" color="text.secondary">{s.name}</Typography></Stack>)}
        <Typography variant="caption" color="text.secondary" data-testid={`${testId}-hover`} sx={{ ml: 'auto', minHeight: 18 }}>
          {at ? `${at.x}: ${series.map((s) => `${s.name} ${typeof at.values[s.key] === 'number' ? num(at.values[s.key] as number, 1) : 'no value'}`).join(' · ')} ${unit}` : 'Hover for exact values'}
        </Typography>
      </Stack>
    </Box>
  );
};

export interface HeatCell { value: number | null; note?: string }
/** Rows × columns heatmap. A null cell is hatched "no data", never a zero colour; clicking a cell drills down. */
export const Heatmap = ({ rows, cols, cell, onCell, diverging, format = (v: number) => num(v), testId = 'heatmap', legend }:
  { rows: string[]; cols: string[]; cell: (r: string, c: string) => HeatCell; onCell?: (r: string, c: string) => void; diverging?: boolean; format?: (v: number) => string; testId?: string; legend?: string }) => {
  const vals = rows.flatMap((r) => cols.map((c) => cell(r, c).value)).filter((v): v is number => v !== null);
  const max = Math.max(1e-9, ...vals.map((v) => Math.abs(v)));
  const min = Math.min(...vals, 0);
  if (vals.length === 0) return <Typography variant="body2" color="text.secondary" data-testid={`${testId}-empty`}>No cell has data.</Typography>;
  return (
    <Box data-testid={testId} sx={{ overflowX: 'auto' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: `minmax(110px, 160px) repeat(${cols.length}, minmax(64px, 1fr))`, gap: '3px', minWidth: 160 + cols.length * 64 }}>
        <Box />
        {cols.map((c) => <Typography key={c} variant="caption" fontWeight={800} color="text.secondary" textAlign="center" noWrap title={c}>{c}</Typography>)}
        {rows.map((r) => (
          <Box key={r} sx={{ display: 'contents' }}>
            <Typography variant="caption" fontWeight={700} noWrap title={r} sx={{ alignSelf: 'center' }}>{r}</Typography>
            {cols.map((c) => {
              const x = cell(r, c);
              const t = x.value === null ? 0 : diverging ? x.value / max : (x.value - min) / (max - min || 1);
              const dark = x.value !== null && (diverging ? Math.abs(t) > 0.62 : t > 0.55);
              return (
                <Tooltip key={c} title={`${r} · ${c}: ${x.value === null ? 'no data' : format(x.value)}${x.note ? ` (${x.note})` : ''}`} arrow>
                  <Box component={onCell ? 'button' : 'div'} onClick={onCell ? () => onCell(r, c) : undefined} data-testid="heat-cell" data-empty={x.value === null ? '1' : '0'}
                    sx={{ border: 0, p: 0, height: 34, borderRadius: 1, display: 'grid', placeItems: 'center', cursor: onCell ? 'pointer' : 'default', font: 'inherit', fontSize: 11.5, fontWeight: 700,
                      color: dark ? '#fff' : colors.ink, '&:focus-visible': { outline: `2px solid ${colors.navy700}` },
                      background: x.value === null ? 'repeating-linear-gradient(45deg,#F2F4F7,#F2F4F7 4px,#E4E7EC 4px,#E4E7EC 8px)' : diverging ? divergingHeat(t) : heat(t) }}>
                    {x.value === null ? '' : format(x.value)}
                  </Box>
                </Tooltip>
              );
            })}
          </Box>
        ))}
      </Box>
      {legend && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>{legend} Hatched cells have no data.</Typography>}
    </Box>
  );
};

export interface RankItem { label: string; value: number | null; sub?: string; key?: string }
/**
 * Ranked bars around a zero axis (negative left in red, positive right in green) for variances; `signed={false}` draws a plain ranking. A null value is shown as "no data", not as a bar.
 */
export const RankBars = ({ items, signed = true, format = (v: number) => num(v), onItem, testId = 'rank-bars', higherIsBetter = true }:
  { items: RankItem[]; signed?: boolean; format?: (v: number) => string; onItem?: (i: RankItem) => void; testId?: string; higherIsBetter?: boolean }) => {
  const max = Math.max(1e-9, ...items.map((i) => Math.abs(i.value ?? 0)));
  if (items.length === 0) return <Typography variant="body2" color="text.secondary" data-testid={`${testId}-empty`}>Nothing to rank.</Typography>;
  return (
    <Stack spacing={0.9} data-testid={testId}>
      {items.map((i) => {
        const v = i.value;
        const w = v === null ? 0 : (Math.abs(v) / max) * (signed ? 50 : 100);
        const good = v !== null && (v >= 0) === higherIsBetter;
        const color = !signed ? colors.green : v === null ? '#98A2B3' : good ? tone.good : tone.bad;
        return (
          <Box key={i.key ?? i.label} component={onItem ? 'button' : 'div'} onClick={onItem ? () => onItem(i) : undefined} data-testid="rank-row"
            sx={{ border: 0, bgcolor: 'transparent', p: 0, textAlign: 'left', font: 'inherit', cursor: onItem ? 'pointer' : 'default', '&:hover': onItem ? { bgcolor: '#F8FAFC' } : undefined, borderRadius: 1 }}>
            <Stack direction="row" justifyContent="space-between" spacing={1}>
              <Typography variant="caption" fontWeight={700} noWrap title={i.label} sx={{ minWidth: 0, flex: 1 }}>{i.label}</Typography>
              <Typography variant="caption" fontWeight={800} sx={{ color: v === null ? 'text.disabled' : signed ? color : 'text.secondary', whiteSpace: 'nowrap' }}>{v === null ? 'no data' : `${signed && v > 0 ? '+' : ''}${format(v)}`}</Typography>
            </Stack>
            {i.sub && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontSize: 11, lineHeight: 1.2 }}>{i.sub}</Typography>}
            <Box sx={{ position: 'relative', height: 8, borderRadius: 4, bgcolor: '#EEF2F8', mt: 0.3 }}>
              {signed && <Box sx={{ position: 'absolute', left: '50%', top: -2, bottom: -2, width: '1px', bgcolor: '#98A2B3' }} />}
              {v !== null && <Box sx={{ position: 'absolute', height: '100%', borderRadius: 4, bgcolor: color, width: `${Math.max(1.5, w)}%`, ...(signed ? (v >= 0 ? { left: '50%' } : { right: '50%' }) : { left: 0 }) }} />}
            </Box>
          </Box>
        );
      })}
    </Stack>
  );
};

/** Grouped vertical bars: one group per category, one bar per series (forecast / target / actual). A missing value leaves a gap and a "–". */
export const GroupedBars = ({ groups, series, height = 220, unit = 'units', onGroup, testId = 'grouped-bars' }:
  { groups: { label: string; values: Record<string, number | null | undefined> }[]; series: ChartSeries[]; height?: number; unit?: string; onGroup?: (label: string) => void; testId?: string }) => {
  const max = Math.max(1, ...groups.flatMap((g) => series.map((s) => g.values[s.key] ?? 0)));
  if (groups.length === 0) return <Typography variant="body2" color="text.secondary" data-testid={`${testId}-empty`}>No groups to compare.</Typography>;
  return (
    <Box data-testid={testId}>
      <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: 1.25, height, overflowX: 'auto', pb: 0.5 }}>
        {groups.map((g) => (
          <Box key={g.label} component={onGroup ? 'button' : 'div'} onClick={onGroup ? () => onGroup(g.label) : undefined}
            sx={{ border: 0, bgcolor: 'transparent', p: 0, flex: '1 0 54px', maxWidth: 140, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', cursor: onGroup ? 'pointer' : 'default', font: 'inherit' }}>
            <Box sx={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: '3px', flex: 1 }}>
              {series.map((s) => {
                const v = g.values[s.key];
                return (
                  <Tooltip key={s.key} title={`${g.label} · ${s.name}: ${typeof v === 'number' ? `${num(v)} ${unit}` : 'no value'}`} arrow>
                    <Box sx={{ width: `${Math.floor(70 / series.length)}%`, minWidth: 8, maxWidth: 22, height: typeof v === 'number' ? `${Math.max(2, (v / max) * 100)}%` : 2, bgcolor: typeof v === 'number' ? s.color : '#E4E7EC', borderRadius: '4px 4px 0 0' }} />
                  </Tooltip>
                );
              })}
            </Box>
            <Typography variant="caption" fontWeight={700} noWrap title={g.label} sx={{ textAlign: 'center', mt: 0.5 }}>{g.label}</Typography>
          </Box>
        ))}
      </Box>
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>{series.map((s) => <Stack key={s.key} direction="row" spacing={0.6} alignItems="center"><Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: s.color }} /><Typography variant="caption" color="text.secondary">{s.name}</Typography></Stack>)}</Stack>
    </Box>
  );
};

/** Days-of-cover bars with the policy thresholds drawn as lines, so "short" and "excess" are visible without reading numbers. */
export const CoverageBars = ({ items, low, high, onItem, testId = 'coverage' }: { items: { label: string; days: number | null; sub?: string }[]; low: number; high: number; onItem?: (label: string) => void; testId?: string }) => {
  const max = Math.max(high * 1.5, ...items.map((i) => i.days ?? 0));
  if (items.length === 0) return <Typography variant="body2" color="text.secondary" data-testid={`${testId}-empty`}>No position to show.</Typography>;
  return (
    <Stack spacing={0.9} data-testid={testId}>
      {items.map((i) => {
        const d = i.days;
        const c = d === null ? '#98A2B3' : d < low ? tone.bad : d > high ? tone.warn : tone.good;
        return (
          <Box key={i.label} component={onItem ? 'button' : 'div'} onClick={onItem ? () => onItem(i.label) : undefined} sx={{ border: 0, bgcolor: 'transparent', p: 0, textAlign: 'left', font: 'inherit', cursor: onItem ? 'pointer' : 'default' }}>
            <Stack direction="row" justifyContent="space-between"><Typography variant="caption" fontWeight={700} noWrap title={i.label} sx={{ flex: 1 }}>{i.label}</Typography><Typography variant="caption" fontWeight={800} sx={{ color: c }}>{d === null ? 'no forecast' : `${num(d)} ${Math.round(d) === 1 ? 'day' : 'days'}`}</Typography></Stack>
            {i.sub && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontSize: 11 }}>{i.sub}</Typography>}
            <Box sx={{ position: 'relative', height: 8, borderRadius: 4, bgcolor: '#EEF2F8', mt: 0.3 }}>
              {d !== null && <Box sx={{ height: '100%', width: `${Math.max(2, Math.min(100, (d / max) * 100))}%`, borderRadius: 4, bgcolor: c }} />}
              {[low, high].map((t) => <Box key={t} sx={{ position: 'absolute', top: -3, bottom: -3, left: `${(t / max) * 100}%`, width: '2px', bgcolor: '#475467', opacity: 0.55 }} />)}
            </Box>
          </Box>
        );
      })}
      <Typography variant="caption" color="text.secondary">Markers: {low} days (shortage below) and {high} days (excess above).</Typography>
    </Stack>
  );
};

/** A right-hand panel for the underlying records of a figure (variance detail, supplier offer, recommendation evidence). */
export const DetailDrawer = ({ open, onClose, title, subtitle, children, width = 520, testId = 'detail-drawer' }: { open: boolean; onClose: () => void; title: ReactNode; subtitle?: ReactNode; children: ReactNode; width?: number; testId?: string }) => (
  <Drawer anchor="right" open={open} onClose={onClose} PaperProps={{ sx: { width: { xs: '100%', sm: width }, maxWidth: '100%' }, 'data-testid': testId } as object}>
    <Stack direction="row" alignItems="flex-start" justifyContent="space-between" sx={{ p: 2, borderBottom: `1px solid ${colors.line}` }}>
      <Box sx={{ minWidth: 0 }}><Typography variant="h6" fontWeight={800}>{title}</Typography>{subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}</Box>
      <IconButton onClick={onClose} aria-label="Close details" size="small"><CloseRounded /></IconButton>
    </Stack>
    <Box sx={{ p: 2, overflowY: 'auto' }}>{children}</Box>
  </Drawer>
);
