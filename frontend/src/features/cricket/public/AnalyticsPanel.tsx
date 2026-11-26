import { useState } from 'react';
import { WagonWheel } from './WagonWheel';
import { Alert, Box, Paper, Stack, Tab, Tabs, Typography } from '@mui/material';
import type { CommentaryLine, InningsAnalytics, OverRuns, WormPoint } from '@/types/cricketPublic';
import { usePublicAnalytics } from './usePublicAnalytics';

/**
 * Charts and commentary under the public scorecard, drawn with plain SVG (no chart library): runs in each over with the wickets marked (the Manhattan), the running
 * total after each over for both innings (the worm), the partnership for each wicket and a line for each ball. Each chart is also a table for people who cannot see
 * it, and says in words what it shows. Everything comes from the server's analytics, which comes from the ball log: nothing here is estimated.
 */
const COLORS = ['#1b7a3a', '#b45309'];
const W = 320;
const H = 160;
const PAD = { left: 30, right: 8, top: 10, bottom: 22 };

const niceMax = (value: number): number => {
  if (value <= 5) {
    return 5;
  }
  const step = value <= 20 ? 5 : value <= 60 ? 10 : value <= 200 ? 20 : 50;
  return Math.ceil(value / step) * step;
};

/** One bar per over; a red dot on the bar for each wicket in that over. */
export const Manhattan = ({ innings }: { innings: InningsAnalytics }) => {
  const overs = innings.overRuns;
  const max = niceMax(Math.max(0, ...overs.map((o) => o.runs)));
  const slot = (W - PAD.left - PAD.right) / Math.max(1, overs.length);
  const y = (runs: number) => H - PAD.bottom - (runs / max) * (H - PAD.top - PAD.bottom);
  const summary = `Runs in each over of the ${innings.battingTeam} innings: ${overs.map((o) => `over ${o.over} ${o.runs}${o.wickets ? ` (${o.wickets} wicket${o.wickets === 1 ? '' : 's'})` : ''}`).join(', ')}.`;
  return (
    <Box data-testid="manhattan">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} style={{ width: '100%', height: 'auto', display: 'block' }}>
        {[0, max / 2, max].map((tick) => (
          <g key={tick}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(tick)} y2={y(tick)} stroke="currentColor" strokeOpacity={0.15} />
            <text x={PAD.left - 4} y={y(tick) + 3} fontSize={9} textAnchor="end" fill="currentColor">{tick}</text>
          </g>
        ))}
        {overs.map((o: OverRuns, i) => {
          const x = PAD.left + i * slot + slot * 0.15;
          const bar = slot * 0.7;
          return (
            <g key={o.over} data-testid="over-bar" data-over={o.over} data-runs={o.runs} data-wickets={o.wickets}>
              <rect x={x} y={y(o.runs)} width={bar} height={Math.max(1, H - PAD.bottom - y(o.runs))} fill={COLORS[0]} opacity={o.complete ? 1 : 0.55} />
              {Array.from({ length: o.wickets }, (_, k) => <circle key={k} cx={x + bar / 2} cy={y(o.runs) - 5 - k * 8} r={3} fill="#c62828" />)}
              <text x={x + bar / 2} y={H - 8} fontSize={9} textAnchor="middle" fill="currentColor">{o.over}</text>
            </g>
          );
        })}
      </svg>
      <table style={{ position: 'absolute', left: -9999 }} aria-label="Runs in each over">
        <tbody>{overs.map((o) => <tr key={o.over}><td>{o.over}</td><td>{o.runs}</td><td>{o.wickets}</td></tr>)}</tbody>
      </table>
    </Box>
  );
};

/** The running total after each over, one line per innings, with the wickets as dots. */
export const Worm = ({ innings }: { innings: InningsAnalytics[] }) => {
  const maxOver = Math.max(1, ...innings.flatMap((i) => i.worm.map((p) => p.over)));
  const maxRuns = niceMax(Math.max(0, ...innings.flatMap((i) => [i.target ? i.target - 1 : 0, ...i.worm.map((p) => p.runs)])));
  const x = (over: number) => PAD.left + (over / maxOver) * (W - PAD.left - PAD.right);
  const y = (runs: number) => H - PAD.bottom - (runs / maxRuns) * (H - PAD.top - PAD.bottom);
  const summary = innings.map((i) => `${i.battingTeam}: ${i.worm.map((p: WormPoint) => `${p.runs}/${p.wickets} after ${p.over} over${p.over === 1 ? '' : 's'}`).join(', ')}`).join('. ');
  return (
    <Box data-testid="worm">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Running total after each over. ${summary}`} style={{ width: '100%', height: 'auto', display: 'block' }}>
        {[0, maxRuns / 2, maxRuns].map((tick) => (
          <g key={tick}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(tick)} y2={y(tick)} stroke="currentColor" strokeOpacity={0.15} />
            <text x={PAD.left - 4} y={y(tick) + 3} fontSize={9} textAnchor="end" fill="currentColor">{tick}</text>
          </g>
        ))}
        {innings.map((inn, k) => (
          <g key={inn.number} data-testid="worm-line" data-team={inn.battingTeam}>
            <polyline fill="none" stroke={COLORS[k % COLORS.length]} strokeWidth={2} points={inn.worm.map((p) => `${x(p.over)},${y(p.runs)}`).join(' ')} />
            {inn.worm.filter((p, i) => i > 0 && p.wickets > inn.worm[i - 1].wickets).map((p) => <circle key={p.over} cx={x(p.over)} cy={y(p.runs)} r={3} fill="#c62828" />)}
            {inn.target && k > 0 && <line x1={PAD.left} x2={W - PAD.right} y1={y(inn.target - 1)} y2={y(inn.target - 1)} stroke={COLORS[k % COLORS.length]} strokeDasharray="4 3" strokeOpacity={0.6} />}
          </g>
        ))}
        {Array.from({ length: maxOver + 1 }, (_, o) => <text key={o} x={x(o)} y={H - 8} fontSize={9} textAnchor="middle" fill="currentColor">{o}</text>)}
      </svg>
      <Stack direction="row" spacing={2} sx={{ mt: 0.5 }}>
        {innings.map((inn, k) => <Typography key={inn.number} variant="caption" sx={{ color: COLORS[k % COLORS.length], fontWeight: 700 }}>● {inn.battingTeam}</Typography>)}
      </Stack>
    </Box>
  );
};

export const Partnerships = ({ innings }: { innings: InningsAnalytics }) => (
  <Box data-testid="partnerships">
    {innings.partnerships.length === 0 && <Typography variant="body2" color="text.secondary">No wicket has fallen yet.</Typography>}
    {innings.partnerships.map((p) => (
      <Stack key={p.wicket} direction="row" justifyContent="space-between" sx={{ py: 0.5, borderBottom: 1, borderColor: 'divider' }} data-testid="partnership">
        <Typography variant="body2">{p.unbroken ? 'Unbroken' : `${p.wicket}${['st', 'nd', 'rd'][p.wicket - 1] ?? 'th'} wicket`}: {p.batterA} and {p.batterB}</Typography>
        <Typography variant="body2" fontWeight={700}>{p.runs} ({p.balls})</Typography>
      </Stack>
    ))}
  </Box>
);

export const Commentary = ({ lines, inningsNames }: { lines: CommentaryLine[]; inningsNames: Record<number, string> }) => {
  const [all, setAll] = useState(false);
  const shown = [...lines].reverse().slice(0, all ? lines.length : 12);
  return (
    <Box data-testid="commentary">
      {lines.length === 0 && <Typography variant="body2" color="text.secondary">No ball has been bowled yet.</Typography>}
      {shown.map((l) => (
        <Stack key={l.seq} direction="row" spacing={1} sx={{ py: 0.5, borderBottom: 1, borderColor: 'divider' }} data-testid="commentary-line" data-wicket={l.wicket} data-boundary={l.boundary}>
          <Typography variant="body2" sx={{ minWidth: 40, fontWeight: 700 }} title={inningsNames[l.innings]}>{l.over}</Typography>
          <Typography variant="body2" sx={{ fontWeight: l.wicket || l.boundary ? 700 : 400, color: l.wicket ? 'error.main' : 'inherit' }}>{l.text}</Typography>
        </Stack>
      ))}
      {!all && lines.length > 12 && <Typography component="button" variant="body2" onClick={() => setAll(true)} sx={{ mt: 1, background: 'none', border: 0, color: 'primary.main', cursor: 'pointer', p: 0 }} data-testid="commentary-all">Show all {lines.length} balls</Typography>}
    </Box>
  );
};

/** Under the scorecard: commentary, runs per over, the worm and the partnerships. */
export const AnalyticsPanel = ({ matchId, seq }: { matchId: string; seq: number | null }) => {
  const { data, failing, loading } = usePublicAnalytics(matchId, seq);
  const [tab, setTab] = useState(0);
  const [inn, setInn] = useState(0);
  if (data === null) {
    return failing ? <Alert severity="info" sx={{ mt: 2 }} data-testid="analytics-failing">The charts could not be loaded right now. The scorecard above is not affected.</Alert> : loading ? null : null;
  }
  if (data.innings.length === 0) {
    return null;
  }
  const chosen = data.innings[Math.min(inn, data.innings.length - 1)];
  const names = Object.fromEntries(data.innings.map((i) => [i.number, i.battingTeam]));
  return (
    <Paper variant="outlined" sx={{ mt: 2, p: 1.5 }} data-testid="analytics-panel">
      <Tabs value={tab} onChange={(_, v: number) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label="Match analysis">
        <Tab label="Commentary" data-testid="tab-commentary" />
        <Tab label="Overs" data-testid="tab-overs" />
        <Tab label="Worm" data-testid="tab-worm" />
        <Tab label="Partnerships" data-testid="tab-partnerships" />
        {data.shots.length > 0 && <Tab label="Wagon wheel" data-testid="tab-wheel" />}
      </Tabs>
      {failing && <Alert severity="info" sx={{ my: 1 }}>Showing the last charts that arrived.</Alert>}
      {(tab === 1 || tab === 3) && data.innings.length > 1 && (
        <Tabs value={Math.min(inn, data.innings.length - 1)} onChange={(_, v: number) => setInn(v)} sx={{ minHeight: 32 }}>
          {data.innings.map((i) => <Tab key={i.number} label={i.battingTeam} sx={{ minHeight: 32, py: 0 }} />)}
        </Tabs>
      )}
      <Box sx={{ pt: 1.5, position: 'relative' }}>
        {tab === 0 && <Commentary lines={data.commentary} inningsNames={names} />}
        {tab === 1 && <Manhattan innings={chosen} />}
        {tab === 2 && <Worm innings={data.innings} />}
        {tab === 3 && <Partnerships innings={chosen} />}
        {tab === 4 && data.shots.length > 0 && <WagonWheel shots={data.shots} />}
      </Box>
    </Paper>
  );
};
