import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Box, Button, Chip, IconButton, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { AutoAwesome, Close, Send } from '@mui/icons-material';
import { useAiStore } from '@/store/aiStore';
import { AssistantMessage } from '@/features/ai/MessageList';
import UnderstoodRequest from '@/features/ai/UnderstoodRequest';
import ProductContextChip from '@/features/ai/ProductContextChip';
import { ErrorState, Thinking } from '@/components/common/States';
import { colors, gradients, shadows } from '@/design/tokens';

export const POPULAR = [
  'Find cricket bats',
  'Best football shoes',
  'Running shoes under ₹5,000',
  'Track my order',
  'I want to return an item',
  'Show my previous orders',
];

interface BoxProps {
  /** `hero` is the large search on the Home page; `compact` sits above the filters on the Products page and on product pages. */
  variant?: 'hero' | 'compact';
  /** `dark` for the navy hero (glass input, light chips), `light` for normal pages. */
  tone?: 'dark' | 'light';
  /** A product page: the AI search starts out knowing which product the customer is looking at (id only; DSports supplies the facts). */
  product?: { productId: string; name: string };
  /** Replaces the default suggestions (for example product-specific questions). */
  examples?: string[];
  title?: string;
}

/**
 * The input of the primary AI interface: ONE box for products, advice, product knowledge, orders, tracking, returns, refunds and reorder.
 * It talks to the same Customer Agent and conversation as the side drawer. "Ask AI" on a product puts that product in context.
 * Simple searches ("cricket bat") are answered by the service deterministically, without a model call.
 */
export const AiSearchBox = ({ variant = 'hero', tone = 'light', product, examples, title }: BoxProps) => {
  const entries = useAiStore((s) => s.entries);
  const sending = useAiStore((s) => s.sending);
  const send = useAiStore((s) => s.send);
  const productContext = useAiStore((s) => s.productContext);
  const comparison = useAiStore((s) => s.comparisonProducts);
  const focusToken = useAiStore((s) => s.focusToken);
  const askAboutProduct = useAiStore((s) => s.askAboutProduct);
  const [draft, setDraft] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);
  const boxRef = useRef<HTMLDivElement | null>(null);
  const hero = variant === 'hero';
  const dark = tone === 'dark';

  useEffect(() => {
    if (product) askAboutProduct(product, { focus: false });
  }, [product?.productId]); // eslint-disable-line react-hooks/exhaustive-deps

  // "Ask AI" on a card: bring the search box into view and focus it.
  useEffect(() => {
    if (focusToken === 0) return;
    boxRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    inputRef.current?.focus();
  }, [focusToken]);

  const placeholder = comparison.length >= 2 ? 'Ask about these products or compare them...'
    : productContext ? 'Ask about this product, compare it, or find alternatives...'
      : entries.length ? 'Ask a follow-up, e.g. "show me something cheaper"' : 'Ask DSports AI anything: find gear, track an order, start a return…';
  const chips = examples ?? (entries.length === 0 ? POPULAR : []);

  const submit = (text: string) => {
    if (!text.trim() || sending) return;
    setDraft('');
    void send(text);
  };
  const onSubmit = (e: FormEvent) => { e.preventDefault(); submit(draft); };

  return (
    <Box data-testid="ai-search" ref={boxRef} sx={{ mb: variant === 'hero' ? 0 : 3 }}>
      {title && (
        <Typography variant="subtitle1" fontWeight={800} sx={{ mb: 1 }} data-testid="ai-search-title">
          <AutoAwesome fontSize="small" color="secondary" sx={{ verticalAlign: 'text-bottom', mr: 0.5 }} />{title}
        </Typography>
      )}
      <Box component="form" onSubmit={onSubmit} role="search" sx={{ maxWidth: hero ? 780 : 'none', mx: hero ? 'auto' : 0 }}>
        <Box sx={{ mb: 0.75, display: 'flex', justifyContent: hero ? 'center' : 'flex-start' }}><ProductContextChip /></Box>
        <TextField
          fullWidth
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={placeholder}
          size={hero ? 'medium' : 'small'}
          inputRef={inputRef}
          inputProps={{ 'aria-label': 'Ask DSports AI', maxLength: 1000, 'data-testid': 'ai-search-input' }}
          InputProps={{
            startAdornment: <InputAdornment position="start"><AutoAwesome color="secondary" /></InputAdornment>,
            endAdornment: (
              <InputAdornment position="end">
                <IconButton type="submit" disabled={sending || !draft.trim()} aria-label="search with DSports AI" data-testid="ai-search-submit"
                  sx={{ background: gradients.ai, color: '#1b1200', '&:hover': { opacity: 0.9, background: gradients.ai }, '&.Mui-disabled': { background: colors.line, color: colors.muted } }}>
                  <Send fontSize="small" />
                </IconButton>
              </InputAdornment>
            ),
            sx: { bgcolor: dark ? 'rgba(255,255,255,0.97)' : 'background.paper', borderRadius: 999, pl: 1.5, pr: 0.75, ...(hero ? { fontSize: '1.08rem', py: 0.4 } : {}),
              boxShadow: dark ? shadows.glow : shadows.card, '& fieldset': { border: dark ? 'none' : undefined } },
          }}
        />
        {chips.length > 0 && (
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1, mt: 1.5, justifyContent: hero ? 'center' : 'flex-start' }} data-testid="ai-examples">
            {chips.map((x) => (
              <Chip key={x} label={x} size="small" variant={dark ? 'filled' : 'outlined'} clickable onClick={() => submit(x)}
                sx={dark ? { bgcolor: 'rgba(255,255,255,0.10)', color: '#fff', border: '1px solid rgba(255,255,255,0.18)', backdropFilter: 'blur(6px)', '&:hover': { bgcolor: 'rgba(184,242,60,0.22)' } } : undefined} />
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  );
};

/** The answer to the latest question, in the page (not in a chat bubble): the AI is part of the shopping experience. */
export const AiSearchResults = () => {
  const entries = useAiStore((s) => s.entries);
  const sending = useAiStore((s) => s.sending);
  const reset = useAiStore((s) => s.reset);
  const setOpen = useAiStore((s) => s.setOpen);
  const retry = useAiStore((s) => s.retry);
  if (entries.length === 0) return null;
  const lastUser = [...entries].reverse().find((e) => e.role === 'user');
  const latest = entries[entries.length - 1];
  return (
    <Box sx={{ mb: 3 }} data-testid="ai-search-results" aria-live="polite">
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }} noWrap>
          {lastUser?.role === 'user' ? <>Your request: <strong>{lastUser.text}</strong></> : null}
        </Typography>
        <Button size="small" onClick={() => setOpen(true)}>Open chat</Button>
        <Button size="small" startIcon={<Close />} onClick={reset} data-testid="ai-search-clear">Clear</Button>
      </Stack>
      {sending && <Box data-testid="ai-loading" sx={{ mb: 1.5 }}><Thinking /></Box>}
      {latest.role === 'assistant' && <>
        <UnderstoodRequest response={latest.response} />
        <AssistantMessage response={latest.response} onNavigate={() => undefined} wide />
      </>}
      {latest.role === 'error' && (
        <Box data-testid="ai-error">
          <ErrorState title={latest.unavailable ? 'DSports AI is taking a break' : "DSports AI couldn't finish that"}
            body={latest.unavailable ? 'AI assistance is temporarily unavailable. You can still use standard product search.' : latest.text}
            onRetry={() => void retry()} />
        </Box>
      )}
    </Box>
  );
};

/** Input plus results together (Products and product pages). The Home page places them in different sections. */
const AiCommerceSearch = (props: BoxProps) => (
  <Box>
    <AiSearchBox {...props} />
    <Box sx={{ mt: 2.5 }}><AiSearchResults /></Box>
  </Box>
);

export default AiCommerceSearch;
