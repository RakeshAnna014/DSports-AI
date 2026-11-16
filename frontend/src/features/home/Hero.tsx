import { Box, Container, Stack, Typography } from '@mui/material';
import { keyframes } from '@mui/material/styles';
import { AutoAwesome, BoltOutlined, LockOutlined, VerifiedUserOutlined } from '@mui/icons-material';
import { HeroArt } from '@/components/common/SportsArt';
import { AiSearchBox } from '@/features/ai/AiCommerceSearch';
import { colors, gradients } from '@/design/tokens';

const drift = keyframes`0%,100%{transform:translate3d(0,0,0) scale(1)}50%{transform:translate3d(18px,-14px,0) scale(1.06)}`;

const TRUST = [
  { icon: <BoltOutlined fontSize="small" />, text: 'Live prices & stock' },
  { icon: <VerifiedUserOutlined fontSize="small" />, text: 'Every fact verified from NisumSports' },
  { icon: <LockOutlined fontSize="small" />, text: 'Nothing changes without your confirmation' },
];

/** The premium hero: dark gradient over original sports artwork, with the AI search as the centrepiece. */
const Hero = () => (
  <Box component="section" aria-label="NisumSports-AI" sx={{ position: 'relative', overflow: 'hidden', background: gradients.hero, color: colors.navy800, pt: { xs: 5, md: 9 }, pb: { xs: 6, md: 10 } }}>
    <Box sx={{ position: 'absolute', inset: 0, opacity: 0.10 }}><HeroArt /></Box>
    <Box aria-hidden sx={{ position: 'absolute', width: 380, height: 380, borderRadius: '50%', top: -120, right: -80, background: 'radial-gradient(circle, rgba(228,87,46,.12), transparent 65%)', animation: `${drift} 14s ease-in-out infinite` }} />
    <Container maxWidth="md" sx={{ position: 'relative', textAlign: 'center' }}>
      <Stack direction="row" spacing={0.75} alignItems="center" justifyContent="center"
        sx={{ width: 'fit-content', mx: 'auto', mb: 2, px: 1.5, py: 0.5, borderRadius: 999, bgcolor: 'rgba(255,255,255,0.7)', border: `1px solid ${colors.line}`, backdropFilter: 'blur(6px)' }}>
        <AutoAwesome sx={{ fontSize: 16, color: colors.orange }} />
        <Typography variant="caption" fontWeight={700} sx={{ letterSpacing: '.12em' }}>YOUR PERSONAL AI SPORTS ASSISTANT</Typography>
      </Stack>
      <Typography component="h1" sx={{ fontFamily: '"Sora", sans-serif', fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1.02, fontSize: { xs: '2.4rem', sm: '3.4rem', md: '4.2rem' } }}>
        PLAY BETTER.<br />
        <Box component="span" sx={{ background: gradients.brand, WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>SHOP SMARTER.</Box>
      </Typography>
      <Typography variant="h6" sx={{ mt: 2, mb: 0.5, fontWeight: 500, color: colors.navy700, fontFamily: '"Inter", sans-serif', fontSize: { xs: '1rem', md: '1.2rem' } }}>
        Find the right equipment. Get personalised recommendations. Track orders. Handle returns. Get instant support.
      </Typography>
      <Typography sx={{ mb: 3.5, color: colors.muted }} variant="body2">Just tell NisumSports what you need.</Typography>
      <AiSearchBox variant="hero" tone="dark" />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0.75, sm: 3 }} justifyContent="center" sx={{ mt: 3.5, color: colors.muted }}>
        {TRUST.map((t) => (
          <Stack key={t.text} direction="row" spacing={0.75} alignItems="center" justifyContent="center">{t.icon}<Typography variant="caption" fontWeight={600}>{t.text}</Typography></Stack>
        ))}
      </Stack>
    </Container>
  </Box>
);

export default Hero;
