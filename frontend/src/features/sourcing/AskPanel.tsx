import { safeHref } from '@/utils/safeUrl';
import { useState } from 'react';
import { Alert, Button, Chip, Link, Paper, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { sourcingApi, sourcingError } from '@/api/sourcing';

const EXAMPLES = ['Which new badminton products should we investigate?', 'What are customers looking for that we do not currently sell?', 'Which products suit the upcoming sporting season?'];

/** Questions are answered by the typed sourcing tools and worded from their results; no model supplies a fact or a number. */
const AskPanel = ({ onOpen }: { onOpen: (id: string) => void }) => {
  const [q, setQ] = useState('');
  const ask = useMutation({ mutationFn: (text: string) => sourcingApi.ask(text) });
  const a = ask.data;
  return (
    <Stack spacing={1.5} data-testid="src-ask">
      <Stack direction="row" spacing={1}>
        <TextField fullWidth size="small" label="Ask about opportunities, suppliers, landed cost or scores" value={q} onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && q.trim().length > 2) ask.mutate(q); }} inputProps={{ 'data-testid': 'ask-input', maxLength: 400 }} />
        <Button variant="contained" disabled={q.trim().length < 3 || ask.isPending} onClick={() => ask.mutate(q)} data-testid="ask-send">Ask</Button>
      </Stack>
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
        {EXAMPLES.map((e) => <Chip key={e} size="small" variant="outlined" clickable label={e} onClick={() => { setQ(e); ask.mutate(e); }} />)}
      </Stack>
      {ask.isError && <Alert severity="warning" data-testid="ask-error">{sourcingError(ask.error)}</Alert>}
      {a && (
        <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="ask-answer">
          <Typography variant="body2">{a.answer}</Typography>
          {a.needs_choice.length > 0 && <Stack direction="row" spacing={0.75} sx={{ mt: 1 }}>{a.needs_choice.map((c) => <Chip key={c.id} clickable label={c.name} onClick={() => onOpen(c.id)} />)}</Stack>}
          {a.opportunity && <Button size="small" onClick={() => onOpen(a.opportunity!.id)}>Open {a.opportunity.name}</Button>}
          {a.evidence.length > 0 && (
            <Stack sx={{ mt: 1 }} data-testid="ask-evidence"><Typography variant="caption" fontWeight={800}>Based on</Typography>
              {a.evidence.slice(0, 8).map((e, i) => <Typography key={i} variant="caption" color="text.secondary">• {e.claim ?? e.supplier} ({e.source}){e.url && <> <Link href={safeHref(e.url)} target="_blank" rel="noopener noreferrer">source</Link></>}</Typography>)}</Stack>
          )}
          {a.limitations.map((l) => <Typography key={l} variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>{l}</Typography>)}
        </Paper>
      )}
    </Stack>
  );
};

export default AskPanel;
