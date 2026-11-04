import { Box, Container, Stack, Typography } from '@mui/material';
import { AutoAwesome, CompareArrows, KeyboardReturn, LocalShippingOutlined, SearchRounded } from '@mui/icons-material';
import { useAiStore } from '@/store/aiStore';
import { gradients } from '@/design/tokens';

const CAPABILITIES = [
  { icon: <SearchRounded />, title: 'Discover', body: 'Describe what you need in your own words: budget, level, preferences.', prompt: 'I am a beginner cricket player. I need a lightweight bat under ₹8,000.' },
  { icon: <CompareArrows />, title: 'Compare & decide', body: 'Side-by-side facts from DSports, with a plain-language recommendation.', prompt: 'Which one is better for a beginner?' },
  { icon: <LocalShippingOutlined />, title: 'Orders & tracking', body: 'See your latest order and where it is, as a live timeline.', prompt: 'Track my latest order' },
  { icon: <KeyboardReturn />, title: 'Returns', body: 'Pick the product, see if it is eligible, and confirm in one tap.', prompt: 'I want to return an item' },
];

/** What the assistant can really do today, each with a prompt that runs it. */
const AiShowcase = () => {
  const send = useAiStore((s) => s.send);
  const run = (prompt: string) => { void send(prompt); document.querySelector('[data-testid="ai-search"]')?.scrollIntoView?.({ behavior: 'smooth', block: 'center' }); };
  return (
    <Box component="section" aria-label="What DSports AI can do" sx={{ background: gradients.aiPanel, color: '#fff', borderRadius: 5, p: { xs: 2.5, md: 5 }, position: 'relative', overflow: 'hidden' }}>
      <Container maxWidth="md" disableGutters>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}><AutoAwesome sx={{ color: 'secondary.light' }} /><Typography variant="overline" sx={{ letterSpacing: '.14em', fontWeight: 800 }}>DSPORTS AI</Typography></Stack>
        <Typography variant="h3" sx={{ mb: 0.5 }}>One conversation, your whole shopping journey</Typography>
        <Typography variant="body1" sx={{ color: 'rgba(255,255,255,0.72)', mb: 3 }}>The assistant answers from live DSports data, shows you real products and orders, and never changes anything without your confirmation.</Typography>
        <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' } }}>
          {CAPABILITIES.map((c) => (
            <Box key={c.title} component="button" type="button" onClick={() => run(c.prompt)}
              sx={{ textAlign: 'left', cursor: 'pointer', color: 'inherit', p: 2, borderRadius: 3, background: gradients.glass, border: '1px solid rgba(255,255,255,0.14)', backdropFilter: 'blur(8px)',
                transition: 'transform .2s, background .2s', '&:hover, &:focus-visible': { transform: 'translateY(-3px)', background: 'rgba(255,255,255,0.14)', outline: 'none' } }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5, color: 'primary.light' }}>{c.icon}<Typography variant="subtitle1" fontWeight={800} sx={{ color: '#fff' }}>{c.title}</Typography></Stack>
              <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.72)', mb: 1 }}>{c.body}</Typography>
              <Typography variant="caption" sx={{ color: 'secondary.light', fontWeight: 700 }}>Try: “{c.prompt}”</Typography>
            </Box>
          ))}
        </Box>
      </Container>
    </Box>
  );
};

export default AiShowcase;
