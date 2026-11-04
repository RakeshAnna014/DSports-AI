import { Box, Container, Link, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import Brand from '@/components/common/Brand';
import { colors } from '@/design/tokens';

const Footer = () => (
  <Box component="footer" sx={{ bgcolor: colors.navy900, color: 'rgba(255,255,255,0.7)', mt: 6, pt: 4, pb: { xs: 11, md: 4 } }}>
    <Container maxWidth="lg">
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} justifyContent="space-between" alignItems={{ md: 'center' }}>
        <Box>
          <Brand tone="light" />
          <Typography variant="body2" sx={{ mt: 1, maxWidth: 380 }}>Play better. Shop smarter. Your AI-powered sports store.</Typography>
        </Box>
        <Stack direction="row" spacing={3} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          {[['Products', '/products'], ['AI Shop', '/ai'], ['Orders', '/orders'], ['Track', '/track'], ['Returns', '/returns'], ['Support', '/support']].map(([label, to]) => (
            <Link key={to} component={RouterLink} to={to} underline="hover" color="inherit" variant="body2">{label}</Link>
          ))}
        </Stack>
      </Stack>
      <Typography variant="caption" sx={{ display: 'block', mt: 3, opacity: 0.6 }}>© {new Date().getFullYear()} DSports AI. Prices, stock and order details always come live from DSports.</Typography>
    </Container>
  </Box>
);

export default Footer;
