import { useState } from 'react';
import { Chip, Stack, Typography } from '@mui/material';
import { supportStaffAi } from '@/api/supportStaffAi';

type Outcome = 'HELPFUL' | 'NOT_HELPFUL' | 'WANTED_PERSON';
const OPTIONS: { value: Outcome; text: string }[] = [{ value: 'HELPFUL', text: 'Yes, this helped' }, { value: 'NOT_HELPFUL', text: 'No, not really' }, { value: 'WANTED_PERSON', text: 'I would like a person' }];

/** The customer's own answer to "did this help?". It is the only input to the assistant's effectiveness figure; it never changes a ticket. */
export const SupportFeedback = ({ conversationId }: { conversationId: string }) => {
  const [sent, setSent] = useState<Outcome | null>(null);
  const [failed, setFailed] = useState(false);
  const send = async (o: Outcome) => {
    try { await supportStaffAi.feedback(conversationId, o); setSent(o); setFailed(false); } catch { setFailed(true); }
  };
  if (sent) return <Typography variant="caption" color="text.secondary" data-testid="support-feedback-thanks">Thanks, your answer was recorded.</Typography>;
  return (
    <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap data-testid="support-feedback">
      <Typography variant="caption" color="text.secondary">Did this help?</Typography>
      {OPTIONS.map((o) => <Chip key={o.value} size="small" clickable variant="outlined" label={o.text} onClick={() => void send(o.value)} data-testid={`feedback-${o.value}`} />)}
      {failed && <Typography variant="caption" color="warning.main">Your answer could not be saved.</Typography>}
    </Stack>
  );
};
