import { useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, Chip, Divider, Drawer, IconButton, InputAdornment, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { Close, RestartAlt, Send, SmartToy } from '@mui/icons-material';
import { useAiStore } from '@/store/aiStore';
import { useAuthStore } from '@/store/authStore';
import MessageList from '@/features/ai/MessageList';
import { Thinking } from '@/components/common/States';
import { colors, gradients } from '@/design/tokens';
import ProductContextChip from '@/features/ai/ProductContextChip';

const GUEST_SUGGESTIONS = [
  'Find a lightweight cricket bat for a beginner under ₹8,000',
  'Show me running shoes under ₹5,000 for weekend running',
  'Which of these bats is better for a beginner?',
  'How do I knock in a new cricket bat?',
];
const CASE_SUGGESTIONS = ['What is the latest on this case?', 'My order has not arrived. Can you check it?', 'Add a message to my case', 'I would like a person to review this case'];
const MEMBER_SUGGESTIONS = [...GUEST_SUGGESTIONS.slice(0, 3), 'I ordered shoes recently. Can I return them?', 'Show my recent orders'];

const AssistantPanel = () => {
  const { open, entries, sending, setOpen, send, reset, caseContext, leaveCase } = useAiStore();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [draft, setDraft] = useState('');
  const endRef = useRef<HTMLDivElement | null>(null);
  const previousAuth = useRef(isAuthenticated);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ behavior: 'smooth' });
  }, [entries, sending]);

  // A conversation belongs to whoever started it: signing in or out starts a fresh one.
  useEffect(() => {
    if (previousAuth.current !== isAuthenticated) {
      previousAuth.current = isAuthenticated;
      reset();
    }
  }, [isAuthenticated, reset]);

  const submit = (text: string) => {
    if (!text.trim()) return;
    setDraft('');
    void send(text);
  };

  return (
    <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: { xs: '100%', sm: 440 } } }} data-testid="ai-panel">
      <Stack direction="row" alignItems="center" sx={{ px: 2, py: 1.25, background: gradients.aiPanel, color: '#fff' }}>
        <SmartToy sx={{ mr: 1, color: 'secondary.light' }} />
        <Typography variant="subtitle1" fontWeight={800} sx={{ flexGrow: 1 }}>DSports AI</Typography>
        <Tooltip title="New conversation"><IconButton color="inherit" size="small" onClick={reset} aria-label="new conversation"><RestartAlt /></IconButton></Tooltip>
        <IconButton color="inherit" size="small" onClick={() => setOpen(false)} aria-label="close assistant"><Close /></IconButton>
      </Stack>
      <Box sx={{ px: 2, pt: 1 }}><ProductContextChip /></Box>
      {caseContext && (
        <Box sx={{ px: 2, pt: 1 }}>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Chip color="primary" variant="outlined" size="small" data-testid="ai-case-context" sx={{ maxWidth: '100%' }}
              label={`About case ${caseContext.caseNumber}${caseContext.subject ? ' · ' + caseContext.subject : ''}`} />
            <Button size="small" onClick={leaveCase} data-testid="ai-leave-case">Leave case</Button>
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
            Advice here is not a decision on your case. I ask you to confirm before I add a message or request a review, and only a person can resolve or close a case.
          </Typography>
        </Box>
      )}
      {!isAuthenticated && (
        <Alert severity="info" icon={false} sx={{ borderRadius: 0 }}>
          You can ask about products and policies. Sign in to ask about your orders, returns and refunds.
        </Alert>
      )}
      <Box sx={{ flexGrow: 1, overflowY: 'auto', bgcolor: colors.surface }}>
        {entries.length === 0 ? (
          <Box sx={{ p: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              {caseContext
                ? `I can look into case ${caseContext.caseNumber} with you: check its status and your order, explain what the DSports records show, and add a message or ask for a person to review it once you confirm.`
                : 'Welcome to DSports AI. Ask me to find or compare products, explain care and policies, or check an order. Prices, stock and order details always come live from DSports.'}
            </Typography>
            <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
              {(caseContext ? CASE_SUGGESTIONS : isAuthenticated ? MEMBER_SUGGESTIONS : GUEST_SUGGESTIONS).map((s) => (
                <Chip key={s} label={s} variant="outlined" clickable onClick={() => submit(s)} />
              ))}
            </Stack>
          </Box>
        ) : (
          <MessageList entries={entries} onNavigate={() => setOpen(false)} />
        )}
        {sending && <Box sx={{ px: 2, pb: 2 }} data-testid="ai-loading"><Thinking /></Box>}
        <div ref={endRef} />
      </Box>
      <Divider />
      <Box component="form" sx={{ p: 1.5 }} onSubmit={(e) => { e.preventDefault(); submit(draft); }}>
        <TextField fullWidth size="small" placeholder={caseContext ? 'Ask about this case…' : 'Ask about products, orders, returns…'} value={draft} onChange={(e) => setDraft(e.target.value)}
          disabled={sending} inputProps={{ 'aria-label': 'message to the assistant', maxLength: 1000, 'data-testid': 'ai-input' }}
          InputProps={{ endAdornment: (
            <InputAdornment position="end">
              <IconButton type="submit" color="primary" disabled={sending || !draft.trim()} aria-label="send message" data-testid="ai-send"><Send /></IconButton>
            </InputAdornment>
          ) }} />
      </Box>
    </Drawer>
  );
};

export default AssistantPanel;
