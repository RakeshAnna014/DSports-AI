import { useState } from 'react';
import { Alert, Box, Button, Chip, Link, Paper, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { planningApi, planningError } from '@/api/planning';
import { usePlanning } from './parts';

const SUGGESTIONS = ['Compare forecast, target and actual sales for badminton', 'Show the forecast accuracy', 'Which stores are at risk of running out of cricket balls?', 'How many units should we allocate to each store?',
  'Can we transfer stock instead of purchasing more?', 'Which regional forecasts are based on insufficient data?', 'What happens to the inventory plan if supplier lead time increases by 7 days for cricket bats?'];

/** Questions are answered by the planning tools over persisted records and worded from their results. No model supplies a number. Every answer links to the records it came from. */
const AskPanel = () => {
  const { goTab, planId } = usePlanning();
  const [q, setQ] = useState('');
  const ask = useMutation({ mutationFn: (text: string) => planningApi.ask(text) });
  const send = (text: string) => { if (text.trim().length >= 3) { setQ(text); ask.mutate(text); } };
  const go = (link: string) => {
    const p = new URLSearchParams(link.split('?')[1] ?? '');
    const tab = p.get('tab') ?? 'dashboard';
    p.delete('tab');
    goTab(tab, Object.fromEntries(p.entries()));
  };
  return (
    <Stack spacing={1.25} data-testid="ask">
      <Alert severity="info" icon={false}>Ask about forecasts, targets, actuals, accuracy, stock-out risk, allocation, transfers, regions, scenarios or how reliable the data is. Answers come from the planning records{planId ? '' : ' (choose a plan for plan questions)'}; anything the data does not hold is reported as unavailable.</Alert>
      <Stack direction="row" spacing={1}>
        <TextField fullWidth size="small" label="Ask about the plan" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') send(q); }} inputProps={{ 'data-testid': 'ask-input', maxLength: 400 }} />
        <Button variant="contained" onClick={() => send(q)} disabled={ask.isPending || q.trim().length < 3} data-testid="ask-send">Ask</Button>
      </Stack>
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>{SUGGESTIONS.map((s) => <Chip key={s} size="small" variant="outlined" label={s} onClick={() => send(s)} />)}</Stack>
      {ask.isError && <Alert severity="warning" data-testid="ask-error">{planningError(ask.error)}</Alert>}
      {ask.data && (
        <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="ask-answer">
          <Typography variant="body2">{ask.data.answer}</Typography>
          {ask.data.evidence.length > 0 && <Box sx={{ mt: 1 }}><Typography variant="caption" fontWeight={800}>Records behind this answer: </Typography>{ask.data.evidence.map((e) => <Link key={e.link} component="button" type="button" variant="caption" onClick={() => go(e.link)} data-testid="ask-evidence" sx={{ mr: 1 }}>{e.label}</Link>)}</Box>}
          {ask.data.limitations.map((l) => <Typography key={l} variant="caption" color="text.secondary" display="block">{l}</Typography>)}
        </Paper>
      )}
    </Stack>
  );
};

export default AskPanel;
