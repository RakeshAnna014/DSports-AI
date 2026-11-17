import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Alert, Box, Button, MenuItem, Stack, TextField } from '@mui/material';
import { cricketApi } from '@/api/cricket';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { RulesFields } from './RulesFields';
import type { BallType, Match, MatchRules } from '@/types/cricket';

/**
 * Change a match's overs, ball and rules before it starts. The server replaces all the settings at once, so the date, city and umpires are sent back
 * unchanged. The edit carries the version the page loaded: if someone else changed the match meanwhile the server answers 409 and we ask for a reload.
 */
export const EditSettingsForm = ({ match, onSaved, onCancel }: { match: Match; onSaved: () => void; onCancel: () => void }) => {
  const [overs, setOvers] = useState(String(match.overs));
  const [ballType, setBallType] = useState<BallType>(match.ballType);
  const [rules, setRules] = useState<MatchRules>(match.rules);
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () => cricketApi.matches.update(match.id, {
      overs: Number(overs), ballType, rules: { ...rules, maxOversPerBowler: rules.maxOversPerBowler ?? undefined } as Partial<MatchRules>,
      scheduledAt: match.scheduledAt ?? undefined, city: match.city ?? undefined, umpires: match.umpires ?? undefined,
    }, match.version),
    onSuccess: onSaved,
    onError: (err) => {
      const code = getApiError(err)?.code;
      setError(code === 'OPTIMISTIC_LOCKING_CONFLICT' ? 'Someone changed this match while you were editing. Cancel and open it again.' : getApiErrorMessage(err, 'The settings could not be saved.'));
    },
  });
  const valid = Number(overs) >= 1 && Number(overs) <= 100 && !Number.isNaN(rules.playersPerSide) && !Number.isNaN(rules.ballsPerOver) && !Number.isNaN(rules.widePenalty) && !Number.isNaN(rules.noBallPenalty);

  return (
    <Box component="form" data-testid="edit-settings-form" onSubmit={(e) => { e.preventDefault(); setError(null); if (valid) save.mutate(); }}>
      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label="Overs per side" type="number" value={overs} onChange={(e) => setOvers(e.target.value)} inputProps={{ min: 1, max: 100 }} fullWidth />
          <TextField size="small" select label="Ball" value={ballType} onChange={(e) => setBallType(e.target.value as BallType)} fullWidth>
            <MenuItem value="TENNIS">Tennis ball</MenuItem>
            <MenuItem value="LEATHER">Leather ball</MenuItem>
          </TextField>
        </Stack>
        <RulesFields rules={rules} onChange={setRules} />
        {error && <Alert severity="error" data-testid="settings-error">{error}</Alert>}
        <Stack direction="row" spacing={1}>
          <Button type="submit" variant="contained" disabled={!valid || save.isPending} data-testid="save-settings">Save settings</Button>
          <Button onClick={onCancel}>Cancel</Button>
        </Stack>
      </Stack>
    </Box>
  );
};
