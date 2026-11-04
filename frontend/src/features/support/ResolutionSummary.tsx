import { Alert, Chip, Paper, Stack, Typography } from '@mui/material';
import type { AiSupport } from '@/types/support';
import { label } from './parts';

type Investigation = Extract<AiSupport, { kind: 'INVESTIGATION' }>;

const NEXT: Record<string, string> = {
  ANSWER: 'Answered from your records',
  ASK_INFO: 'I need one more detail',
  SAFE_NEXT_STEP: 'A step you can take',
  CREATE_CASE: 'A person should follow this up',
  ESCALATE: 'A person should review this',
  PERFORM_ACTION: 'An action is ready for your confirmation',
  UNVERIFIABLE: 'I could not check this right now',
};

/** What the deterministic rules concluded. The recommendation is a recommendation: nothing has been done, and a requested review is not an assigned person. */
export const ResolutionSummary = ({ s }: { s: Investigation }) => {
  const r = s.resolution;
  return (
    <Paper variant="outlined" data-testid="support-resolution" data-outcome={r.outcome} sx={{ p: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.75 }}>
        <Typography variant="subtitle2" fontWeight={900}>{NEXT[r.outcome] ?? label(r.outcome)}</Typography>
        <Chip size="small" variant="outlined" label={label(s.complaint)} />
        {s.safety_related && <Chip size="small" color="error" label="Safety concern reported" />}
      </Stack>
      <Typography variant="body2" sx={{ mb: 0.75 }}>{r.recommendation}</Typography>
      {r.escalation.recommended && (
        <Alert severity="info" icon={false} sx={{ py: 0 }} data-testid="support-escalation-recommended">
          A person review is recommended: {r.escalation.reason}. It becomes a request only when you confirm, and it counts as accepted only when a support executive takes the case.
        </Alert>
      )}
      {r.reasons.length > 0 && (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>Why: {r.reasons.join(' · ')}</Typography>
      )}
    </Paper>
  );
};
