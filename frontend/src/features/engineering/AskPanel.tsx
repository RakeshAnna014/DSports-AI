import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, FormControlLabel, Paper, Skeleton, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { engineeringApi, type EngInvestigation } from '@/api/engineeringAi';

const EXAMPLES = ['Why did checkout fail?', 'What caused the increase in HTTP 5xx errors?', 'Show recurring database timeout errors', 'What errors appeared after the latest deployment?'];

export const errorText = (e: unknown) => {
  const r = (e as { response?: { status?: number; data?: { message?: string } } })?.response;
  return r?.status === 403 ? 'Engineering AI is restricted to authorised engineering users.' : r?.data?.message ?? 'The investigation could not be completed. The rest of the shop is unaffected.';
};

const Result = ({ r }: { r: EngInvestigation }) => (
  <Stack spacing={2} data-testid="eng-result">
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="subtitle1" fontWeight={800}>Summary</Typography>
        <Chip size="small" label={r.summary.source === 'llm' ? 'AI-written, checked against the evidence' : 'Deterministic (no AI)'} color={r.summary.source === 'llm' ? 'secondary' : 'default'} data-testid="eng-summary-source" />
      </Stack>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }} data-testid="eng-summary">{r.summary.text}</Typography>
      {r.summary.guard?.reason && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>{r.summary.guard.reason}</Typography>}
    </Paper>
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="subtitle2" fontWeight={800}>Observed facts <Chip size="small" label="from log records" sx={{ ml: 1 }} /></Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{r.facts.map((f) => <Typography key={f} component="li" variant="body2">{f}</Typography>)}</Box>
    </Paper>
    {r.hypotheses.length > 0 && (
      <Paper variant="outlined" sx={{ p: 2, borderColor: 'warning.main' }} data-testid="eng-hypotheses">
        <Typography variant="subtitle2" fontWeight={800}>Hypotheses <Chip size="small" color="warning" label="NOT confirmed" sx={{ ml: 1 }} /></Typography>
        {r.hypotheses.map((h) => (
          <Box key={h.statement} sx={{ mt: 1 }}>
            <Typography variant="body2">{h.statement}</Typography>
            <Typography variant="caption" color="text.secondary">Confidence {h.confidence} · to confirm: {h.to_confirm}{h.evidence_ids.length ? ` · evidence ${h.evidence_ids.map((i) => `L${i}`).join(', ')}` : ''}</Typography>
          </Box>
        ))}
      </Paper>
    )}
    {r.clusters.length > 0 && (
      <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto' }}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 1 }}>Error clusters</Typography>
        <Table size="small" data-testid="eng-clusters"><TableHead><TableRow><TableCell>Count</TableCell><TableCell>Signature</TableCell><TableCell>Category</TableCell><TableCell>Services</TableCell><TableCell>Change</TableCell></TableRow></TableHead>
          <TableBody>{r.clusters.map((c) => (
            <TableRow key={c.signature}><TableCell>{c.count}</TableCell><TableCell sx={{ maxWidth: 420 }}>{c.text}</TableCell><TableCell>{c.category}</TableCell><TableCell>{c.services.join(', ')}</TableCell>
              <TableCell>{c.change.replace(/_/g, ' ').toLowerCase()}{c.previous_window_count != null ? ` (was ${c.previous_window_count})` : ''}</TableCell></TableRow>))}</TableBody></Table>
      </Paper>
    )}
    {r.evidence.length > 0 && (
      <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto' }}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 1 }}>Evidence ({r.evidence.length} records, redacted)</Typography>
        <Table size="small" data-testid="eng-evidence"><TableHead><TableRow><TableCell>Id</TableCell><TableCell>Time (UTC)</TableCell><TableCell>Service</TableCell><TableCell>Level</TableCell><TableCell>Correlation</TableCell><TableCell>Message</TableCell></TableRow></TableHead>
          <TableBody>{r.evidence.map((e) => (
            <TableRow key={e.id}><TableCell>L{e.id}</TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{e.ts.replace('T', ' ').slice(0, 19)}</TableCell>
              <TableCell>{e.service} {e.origin === 'synthetic' && <Chip size="small" color="warning" label="synthetic" />}</TableCell><TableCell>{e.level}</TableCell>
              <TableCell sx={{ fontFamily: 'monospace', fontSize: 11 }}>{e.correlation_id?.slice(0, 8) ?? ''}</TableCell>
              <TableCell sx={{ maxWidth: 480 }}>{e.message}{e.exception_head ? ` | ${e.exception_head}` : ''}{e.injection_flagged && <Chip size="small" color="error" label="instruction-like text withheld" sx={{ ml: 1 }} />}</TableCell></TableRow>))}</TableBody></Table>
      </Paper>
    )}
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="subtitle2" fontWeight={800}>Next steps</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{r.next_steps.map((s) => <Typography key={s} component="li" variant="body2">{s}</Typography>)}</Box>
      <Typography variant="subtitle2" fontWeight={800} sx={{ mt: 1.5 }}>Limitations</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{r.limitations.map((s) => <Typography key={s} component="li" variant="caption" color="text.secondary">{s}</Typography>)}</Box>
      <Typography variant="caption" color="text.disabled" display="block" sx={{ mt: 1 }}>{r.interpretation.join(' · ')} · {r.meta.latency_ms} ms</Typography>
    </Paper>
  </Stack>
);


/** Asking a question in words (the original workflow): the same investigation, kept as one tab. */
const AskPanel = ({ origin }: { origin: '' | 'real' | 'synthetic' }) => {
  const [question, setQuestion] = useState('');
  const [useLlm, setUseLlm] = useState(false);
  const ask = useMutation({ mutationFn: () => engineeringApi.investigate(question.trim(), origin || undefined, useLlm) });
  return (
    <Box data-testid="eng-ask-panel">
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 1 }}>
        <TextField fullWidth size="small" label="Ask about an incident" value={question} onChange={(e) => setQuestion(e.target.value)} inputProps={{ maxLength: 500, 'data-testid': 'eng-question' }}
          onKeyDown={(e) => { if (e.key === 'Enter' && question.trim().length >= 3 && !ask.isPending) ask.mutate(); }} />
        <Button variant="contained" disabled={question.trim().length < 3 || ask.isPending} onClick={() => ask.mutate()} data-testid="eng-ask">Investigate</Button>
      </Stack>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center" sx={{ mb: 2 }}>
        {EXAMPLES.map((e) => <Chip key={e} size="small" clickable label={e} onClick={() => setQuestion(e)} />)}
        <FormControlLabel control={<Switch size="small" checked={useLlm} onChange={(e) => setUseLlm(e.target.checked)} />} label="AI-written summary (checked against the evidence)" />
      </Stack>
      {ask.isPending && <Skeleton variant="rounded" height={160} data-testid="eng-loading" />}
      {ask.isError && <Alert severity="warning" data-testid="eng-error">{errorText(ask.error)}</Alert>}
      {ask.data && <Result r={ask.data} />}
    </Box>
  );
};

export default AskPanel;