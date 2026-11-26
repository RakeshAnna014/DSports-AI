import { FormControlLabel, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import type { MatchRules } from '@/types/cricket';

const num = (v: string) => (v.trim() === '' ? NaN : Number(v));
const show = (n: number) => (Number.isNaN(n) ? '' : n);

/** The match rules as form fields. Shared by "new match" and "edit settings" so both offer exactly the same rules. The server validates the ranges. */
export const RulesFields = ({ rules, onChange }: { rules: MatchRules; onChange: (rules: MatchRules) => void }) => {
  const set = <K extends keyof MatchRules>(key: K, value: MatchRules[K]) => onChange({ ...rules, [key]: value });
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>Rules (standard unless you change them)</Typography>
      <Stack spacing={1.5}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label="Players per side" type="number" value={show(rules.playersPerSide)} onChange={(e) => set('playersPerSide', num(e.target.value))} inputProps={{ min: 2, max: 11 }} fullWidth />
          <TextField size="small" label="Balls per over" type="number" value={show(rules.ballsPerOver)} onChange={(e) => set('ballsPerOver', num(e.target.value))} inputProps={{ min: 4, max: 10 }} fullWidth />
          <TextField size="small" label="Max overs per bowler" type="number" value={rules.maxOversPerBowler ?? ''} placeholder="set by match and team size"
            onChange={(e) => set('maxOversPerBowler', e.target.value.trim() === '' ? null : Number(e.target.value))} fullWidth />
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label="Wide penalty (runs)" type="number" value={show(rules.widePenalty)} onChange={(e) => set('widePenalty', num(e.target.value))} inputProps={{ min: 0, max: 5 }} fullWidth />
          <TextField size="small" label="No-ball penalty (runs)" type="number" value={show(rules.noBallPenalty)} onChange={(e) => set('noBallPenalty', num(e.target.value))} inputProps={{ min: 0, max: 5 }} fullWidth />
        </Stack>
        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
          <FormControlLabel control={<Switch checked={rules.freeHitEnabled} onChange={(e) => set('freeHitEnabled', e.target.checked)} />} label="Free hit after a no-ball" />
          <FormControlLabel control={<Switch checked={rules.lastManStands} onChange={(e) => set('lastManStands', e.target.checked)} />} label="Last man stands" />
        </Stack>
      </Stack>
    </Paper>
  );
};
