import { useState } from 'react';
import { Box, FormControl, InputLabel, MenuItem, Select, Typography, useTheme } from '@mui/material';
import { toView } from '../shotGeometry';
import type { ShotMark } from '@/types/cricketPublic';

/** The look of a ball is its line, not only its colour: singles to threes thin, fours solid and thick, sixes dashed and thick. */
const style = (runs: number, boundary: boolean) => (boundary && runs >= 6 ? { width: 3, dash: '6 3' } : boundary ? { width: 3, dash: undefined } : { width: 1.5, dash: undefined });

export const WagonWheel = ({ shots }: { shots: ShotMark[] }) => {
  const theme = useTheme();
  const batters = Array.from(new Set(shots.map((s) => s.batter)));
  const [who, setWho] = useState('');
  const shown = who === '' ? shots : shots.filter((s) => s.batter === who);
  const summary = `Wagon wheel: ${shown.length} ${shown.length === 1 ? 'shot' : 'shots'} marked${who ? ` for ${who}` : ''}, ${shown.filter((s) => s.runs === 4 && s.boundary).length} fours and ${shown.filter((s) => s.runs === 6 && s.boundary).length} sixes.`;
  return (
    <Box data-testid="wagon-wheel">
      {batters.length > 1 && (
        <FormControl size="small" sx={{ minWidth: 160, mb: 1 }}>
          <InputLabel id="wheel-batter">Batter</InputLabel>
          <Select labelId="wheel-batter" label="Batter" value={who} onChange={(e) => setWho(e.target.value)} data-testid="wheel-batter">
            <MenuItem value="">Everyone</MenuItem>
            {batters.map((b) => <MenuItem key={b} value={b}>{b}</MenuItem>)}
          </Select>
        </FormControl>
      )}
      <svg viewBox="-110 -110 220 220" width="100%" style={{ maxWidth: 360, display: 'block', margin: '0 auto' }} role="img" aria-label={summary}>
        <circle r={100} fill={theme.palette.success.light} fillOpacity={0.2} stroke={theme.palette.success.main} strokeWidth={1.5} />
        <circle r={50} fill="none" stroke={theme.palette.text.disabled} strokeDasharray="3 3" />
        <rect x={-3} y={-14} width={6} height={28} fill={theme.palette.warning.light} />
        {shown.map((s) => {
          const p = toView({ x: s.x, y: s.y });
          const st = style(s.runs, s.boundary);
          return <line key={s.seq} data-testid="wheel-line" data-runs={s.runs} x1={0} y1={0} x2={p.cx} y2={p.cy} stroke={s.boundary ? theme.palette.primary.main : theme.palette.text.secondary} strokeWidth={st.width} strokeDasharray={st.dash} strokeLinecap="round" />;
        })}
      </svg>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ textAlign: 'center' }}>{summary} Thin: 1-3 runs. Thick: four. Dashed and thick: six. The bowling end is at the top.</Typography>
    </Box>
  );
};
