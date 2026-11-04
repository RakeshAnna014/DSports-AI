import { useEffect, useRef } from 'react';
import { Box, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { AutoAwesome, Close } from '@mui/icons-material';
import { AiSearchBox, POPULAR } from '@/features/ai/AiCommerceSearch';
import { AssistantMessage } from '@/features/ai/MessageList';
import UnderstoodRequest from '@/features/ai/UnderstoodRequest';
import { ErrorState, Thinking } from '@/components/common/States';
import { useAiStore } from '@/store/aiStore';
import { gradients } from '@/design/tokens';

/**
 * The AI Shop: one continuous shopping conversation, full width. Each answer is a component (products, comparison, timeline, return
 * options), not a paragraph, and every card has its own actions, so the customer can click instead of type.
 */
const AiShopPage = () => {
  const entries = useAiStore((s) => s.entries);
  const sending = useAiStore((s) => s.sending);
  const send = useAiStore((s) => s.send);
  const reset = useAiStore((s) => s.reset);
  const retry = useAiStore((s) => s.retry);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => { endRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' }); }, [entries.length, sending]);

  return (
    <Box>
      <Paper sx={{ p: { xs: 2.5, md: 3.5 }, mb: 2.5, color: '#fff', background: gradients.aiPanel, borderRadius: 5 }}>
        <Stack direction="row" spacing={1} alignItems="center"><AutoAwesome sx={{ color: 'secondary.light' }} /><Typography variant="overline" fontWeight={800} sx={{ letterSpacing: '.14em' }}>AI SHOP</Typography></Stack>
        <Typography variant="h3" component="h1">What are you looking for?</Typography>
        <Typography variant="body2" sx={{ color: 'rgba(255,255,255,.72)', mt: 0.5, mb: 2 }}>Ask for products, advice, orders, tracking or returns. Prices, stock and order facts are always live from DSports.</Typography>
        <AiSearchBox variant="compact" tone="dark" examples={entries.length ? [] : POPULAR} />
      </Paper>

      {entries.length > 0 && (
        <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1 }}>
          <Button size="small" startIcon={<Close />} onClick={reset} data-testid="ai-search-clear">Start over</Button>
        </Stack>
      )}
      <Stack spacing={2.5} data-testid="ai-feed" role="log" aria-live="polite">
        {entries.map((e) => {
          if (e.role === 'user') {
            return (
              <Box key={e.id} sx={{ alignSelf: 'flex-end', maxWidth: { xs: '92%', md: '70%' } }} data-testid="ai-user-message">
                <Chip label={e.text} sx={{ height: 'auto', py: 1, px: 0.5, color: '#fff', background: gradients.aiPanel, '& .MuiChip-label': { whiteSpace: 'normal' } }} />
              </Box>
            );
          }
          if (e.role === 'error') {
            return <ErrorState key={e.id} title={e.unavailable ? 'DSports AI is taking a break' : "DSports AI couldn't finish that"}
              body={e.unavailable ? 'AI assistance is temporarily unavailable. You can still browse and search products normally.' : e.text} onRetry={() => void retry()} />;
          }
          return (
            <Box key={e.id}>
              <UnderstoodRequest response={e.response} />
              <AssistantMessage response={e.response} onNavigate={() => undefined} wide />
            </Box>
          );
        })}
        {sending && <Thinking />}
        <div ref={endRef} />
      </Stack>
      {entries.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', mt: 2 }}>
          Try “<Box component="span" sx={{ cursor: 'pointer', color: 'primary.dark', fontWeight: 700 }} onClick={() => void send('Find cricket bats under ₹10,000')}>Find cricket bats under ₹10,000</Box>”.
        </Typography>
      )}
    </Box>
  );
};

export default AiShopPage;
