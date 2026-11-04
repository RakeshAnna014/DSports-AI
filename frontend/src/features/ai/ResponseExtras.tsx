import { Alert, Box, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { CheckCircleOutline } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { ErrorState } from '@/components/common/States';
import type { AiResponse, AiSuggestion } from '@/types/ai';

/** Contextual follow-up actions: tap instead of typing. Plain chips send a message; action chips run the step the service resolved. */
export const SuggestionChips = ({ suggestions, onPick, disabled }: { suggestions: AiSuggestion[]; onPick: (s: AiSuggestion) => void; disabled?: boolean }) => (
  suggestions.length === 0 ? null : (
    <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.75, mt: 1 }} data-testid="ai-suggestions" role="group" aria-label="Suggested follow-ups">
      {suggestions.map((s) => (
        <Chip key={`${s.label}-${s.choiceId ?? ''}`} label={s.label} clickable disabled={disabled} color={s.choiceId ? 'primary' : 'default'} variant={s.choiceId ? 'filled' : 'outlined'}
          onClick={() => onPick(s)} data-testid="ai-suggestion" />
      ))}
    </Stack>
  )
);

/** A change waiting for the customer's confirmation (or just completed): never silent. */
export const ConfirmationCard = ({ response }: { response: AiResponse }) => {
  const action = response.actions[0];
  const navigate = useNavigate();
  if (action && response.requiresConfirmation) {
    // The confirmation itself happens in the dialog (one place, one set of buttons); this is the summary of what is waiting.
    return (
      <Paper variant="outlined" sx={{ p: 2, borderColor: 'secondary.light', bgcolor: 'rgba(255,122,26,0.05)' }} data-testid="ai-confirmation">
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 0.5 }}>Waiting for your confirmation</Typography>
        <Typography variant="body2">{action.summary}</Typography>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>Nothing happens until you confirm. This option expires in a few minutes.</Typography>
      </Paper>
    );
  }
  if (response.actionResult) {
    const result = response.actionResult as { message?: string; summary?: string; cart_updated?: boolean };
    return (
      <Alert icon={<CheckCircleOutline />} severity="success" data-testid="ai-action-result"
        action={result.cart_updated ? <Button color="inherit" size="small" onClick={() => navigate('/cart')}>View cart</Button> : undefined}>
        {result.message ?? result.summary ?? response.message}
      </Alert>
    );
  }
  return null;
};

/** A friendly error with a retry; the raw backend text is never shown. */
export const ResponseError = ({ response, onRetry }: { response: AiResponse; onRetry: () => void }) => {
  const down = response.status === 'ai_unavailable';
  return (
    <ErrorState
      title={down ? 'DSports AI is taking a break' : "DSports AI couldn't finish that"}
      body={down ? 'AI assistance is temporarily unavailable. You can still use standard product search; orders, cart and checkout are not affected.'
        : 'Something went wrong while handling your request. Please try again.'}
      onRetry={onRetry} />
  );
};

export const Reasons = ({ items }: { items: string[] }) => (
  <Box>{items.map((r) => <Typography key={r} variant="caption" display="block" color="text.secondary">✓ {r}</Typography>)}</Box>
);
