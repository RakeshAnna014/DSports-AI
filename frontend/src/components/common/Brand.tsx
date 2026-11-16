import { Box, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { colors } from '@/design/tokens';

interface Props {
  /** Light text for dark backgrounds (the header), dark text for light ones. */
  tone?: 'light' | 'dark';
  to?: string;
}

/** The official Nisum logo (from the supplied Nisum template) followed by the product name "Sports-AI". */
const Brand = ({ tone = 'light', to = '/' }: Props) => (
  <Box component={RouterLink} to={to} aria-label="NisumSports-AI home" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.6, textDecoration: 'none', whiteSpace: 'nowrap' }}>
    <Box component="img" src={tone === 'light' ? '/brand/nisum-logo-white.png' : '/brand/nisum-logo.png'} alt="Nisum" sx={{ height: { xs: 16, sm: 22 }, width: 'auto', display: 'block' }} />
    <Typography component="span" sx={{ fontFamily: '"Sora", sans-serif', fontWeight: 800, fontSize: { xs: '0.85rem', sm: '1.1rem' }, letterSpacing: '-0.01em', color: tone === 'light' ? '#fff' : 'text.primary' }}>
      Sports<Box component="span" sx={{ color: tone === 'light' ? colors.accentOnDark : colors.orange, ml: 0.4, letterSpacing: '0.08em' }}>-AI</Box>
    </Typography>
  </Box>
);

export default Brand;
