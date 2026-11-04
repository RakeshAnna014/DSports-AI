import { useMemo, useState } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { colors } from '@/design/tokens';
import type { ForecastPoint, HistoryPoint } from '@/types/inventoryAi';
import { num, shortDate } from './ui';

const W = 860;
const H = 280;
const PAD = { l: 58, r: 14, t: 16, b: 34 };

/** History (solid) + forecast (green) + 80% interval (band). Real series only; hover shows the exact values. */
export const ForecastChart = ({ history, daily, testId = 'forecast-chart' }: { history: HistoryPoint[]; daily: ForecastPoint[]; testId?: string }) => {
  const [hover, setHover] = useState<number | null>(null);
  const geo = useMemo(() => {
    const all = [...history.map((h) => h.units), ...daily.map((d) => d.hi)];
    const max = Math.max(1, ...all) * 1.08;
    const n = history.length + daily.length;
    const x = (i: number) => PAD.l + (i / Math.max(1, n - 1)) * (W - PAD.l - PAD.r);
    const y = (v: number) => PAD.t + (1 - v / max) * (H - PAD.t - PAD.b);
    const line = (pts: [number, number][]) => pts.map(([a, b], i) => `${i ? 'L' : 'M'}${a.toFixed(1)},${b.toFixed(1)}`).join(' ');
    const hist = history.map((h, i) => [x(i), y(h.units)] as [number, number]);
    const off = history.length;
    const fc = daily.map((d, i) => [x(off + i), y(d.mean)] as [number, number]);
    const hi = daily.map((d, i) => [x(off + i), y(d.hi)] as [number, number]);
    const lo = daily.map((d, i) => [x(off + i), y(d.lo)] as [number, number]).reverse();
    return { max, x, y, n, off, hist, fc, band: `${line(hi)} L${lo.map(([a, b]) => `${a.toFixed(1)},${b.toFixed(1)}`).join(' L')} Z`, histPath: line(hist), fcPath: line([hist[hist.length - 1], ...fc].filter(Boolean)) };
  }, [history, daily]);
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => geo.max * t);
  const at = hover != null ? (hover < geo.off ? { date: history[hover].day, text: `${num(history[hover].units)} units sold` } : { date: daily[hover - geo.off].day,
    text: `forecast ${num(daily[hover - geo.off].mean, 1)} (80%: ${num(daily[hover - geo.off].lo, 1)}–${num(daily[hover - geo.off].hi, 1)})` }) : null;
  return (
    <Box data-testid={testId} sx={{ width: '100%' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Demand history and forecast"
        onMouseLeave={() => setHover(null)}
        onMouseMove={(e) => {
          const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          const px = ((e.clientX - r.left) / r.width) * W;
          setHover(Math.max(0, Math.min(geo.n - 1, Math.round(((px - PAD.l) / (W - PAD.l - PAD.r)) * (geo.n - 1)))));
        }}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.l} x2={W - PAD.r} y1={geo.y(t)} y2={geo.y(t)} stroke={colors.line} />
            <text x={PAD.l - 6} y={geo.y(t) + 4} fontSize="14" textAnchor="end" fill={colors.muted}>{num(t)}</text>
          </g>
        ))}
        <path d={geo.band} fill={colors.green} opacity={0.14} data-testid="forecast-band" />
        <path d={geo.histPath} fill="none" stroke={colors.navy600} strokeWidth={1.6} />
        <path d={geo.fcPath} fill="none" stroke={colors.green} strokeWidth={2.2} strokeDasharray="0" data-testid="forecast-line" />
        <line x1={geo.x(geo.off - 0.5)} x2={geo.x(geo.off - 0.5)} y1={PAD.t} y2={H - PAD.b} stroke={colors.orange} strokeDasharray="4 4" />
        <text x={geo.x(geo.off - 0.5) + 5} y={PAD.t + 10} fontSize="14" fill={colors.orange} fontWeight={700}>today</text>
        {[0, Math.floor(geo.n / 4), Math.floor(geo.n / 2), Math.floor((3 * geo.n) / 4), geo.n - 1].map((i) => {
          const d = i < geo.off ? history[i]?.day : daily[i - geo.off]?.day;
          return d ? <text key={i} x={geo.x(i)} y={H - 8} fontSize="14" textAnchor={i === 0 ? 'start' : i === geo.n - 1 ? 'end' : 'middle'} fill={colors.muted}>{shortDate(d)}</text> : null;
        })}
        {hover != null && <line x1={geo.x(hover)} x2={geo.x(hover)} y1={PAD.t} y2={H - PAD.b} stroke={colors.navy700} strokeOpacity={0.4} />}
      </svg>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 0.5, flexWrap: 'wrap' }}>
        <Legend color={colors.navy600} label="Units sold (history)" />
        <Legend color={colors.green} label="Forecast" />
        <Legend color={colors.green} label="80% interval" faded />
        <Typography variant="caption" color="text.secondary" data-testid="chart-hover" sx={{ ml: 'auto' }}>{at ? `${shortDate(at.date)}: ${at.text}` : 'Hover the chart for exact values'}</Typography>
      </Stack>
    </Box>
  );
};

const Legend = ({ color, label, faded }: { color: string; label: string; faded?: boolean }) => (
  <Stack direction="row" spacing={0.6} alignItems="center">
    <Box sx={{ width: 14, height: 8, borderRadius: 1, bgcolor: color, opacity: faded ? 0.25 : 1 }} />
    <Typography variant="caption" color="text.secondary">{label}</Typography>
  </Stack>
);

export interface BarItem { label: string; value: number; color?: string; sub?: string }

/** Horizontal bars: ranking, coverage, aging distribution. */
export const BarList = ({ items, format = (v: number) => num(v), max, testId }: { items: BarItem[]; format?: (v: number) => string; max?: number; testId?: string }) => {
  const m = max ?? Math.max(1, ...items.map((i) => Math.abs(i.value)));
  return (
    <Stack spacing={0.9} data-testid={testId}>
      {items.map((i) => (
        <Box key={i.label}>
          <Stack direction="row" justifyContent="space-between" spacing={1} alignItems="baseline">
            <Typography variant="caption" fontWeight={600} noWrap sx={{ flex: 1, minWidth: 0 }} title={i.label}>{i.label}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}>{format(i.value)}</Typography>
          </Stack>
          {i.sub && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontSize: 11, lineHeight: 1.2, mb: 0.3 }}>{i.sub}</Typography>}
          <Box sx={{ height: 8, borderRadius: 4, bgcolor: '#EEF2F8', overflow: 'hidden' }}>
            <Box sx={{ height: '100%', width: `${Math.max(2, (Math.abs(i.value) / m) * 100)}%`, bgcolor: i.color ?? colors.green, borderRadius: 4 }} />
          </Box>
        </Box>
      ))}
    </Stack>
  );
};

/** One small bar chart per metric, one bar per scenario; negatives are drawn below the axis. Used for promotion scenario comparison. */
export const MiniBars = ({ title, items, format, testId }: { title: string; items: { label: string; value: number; highlight?: boolean }[]; format: (v: number) => string; testId?: string }) => {
  const max = Math.max(1, ...items.map((i) => Math.abs(i.value)));
  const hasNeg = items.some((i) => i.value < 0);
  const h = 84;
  const base = hasNeg ? h / 2 : h;
  return (
    <Box data-testid={testId} sx={{ minWidth: 0 }}>
      <Typography variant="caption" fontWeight={800} color="text.secondary">{title}</Typography>
      <Stack direction="row" spacing={1} alignItems="flex-end" sx={{ height: h + 4, mt: 0.5, position: 'relative' }}>
        {items.map((i) => {
          const len = (Math.abs(i.value) / max) * (hasNeg ? h / 2 - 4 : h - 4);
          return (
            <Box key={i.label} sx={{ flex: 1, height: h, position: 'relative' }} title={`${i.label}: ${format(i.value)}`}>
              <Box sx={{ position: 'absolute', left: '15%', right: '15%', borderRadius: 1, bgcolor: i.value < 0 ? '#D92D20' : i.highlight ? colors.green : '#9AA6BD',
                ...(i.value < 0 ? { top: base, height: len } : { bottom: h - base, height: Math.max(2, len) }) }} />
            </Box>
          );
        })}
      </Stack>
      <Stack direction="row" spacing={1}>
        {items.map((i) => (
          <Box key={i.label} sx={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
            <Typography variant="caption" fontWeight={i.highlight ? 800 : 500} sx={{ display: 'block', fontSize: 11 }} noWrap>{i.label}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontSize: 10.5 }} noWrap>{format(i.value)}</Typography>
          </Box>
        ))}
      </Stack>
    </Box>
  );
};
