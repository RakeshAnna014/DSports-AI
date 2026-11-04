import { Box, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { gradients } from '@/design/tokens';

/** The DSports AI mark: a rounded "D" with motion lines, and a wordmark with an AI sparkle. */
export const LogoMark = ({ size = 34 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 40 40" role="img" aria-label="DSports AI logo" focusable="false">
    <defs>
      <linearGradient id="dsLogo" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stopColor="#16A34A" />
        <stop offset="1" stopColor="#B8F23C" />
      </linearGradient>
    </defs>
    <rect x="1" y="1" width="38" height="38" rx="11" fill="url(#dsLogo)" />
    <path d="M13 10h7.5c6.2 0 10 3.6 10 10s-3.8 10-10 10H13V10z" fill="#070C18" />
    <path d="M17 14.5h3.2c3.4 0 5.6 2 5.6 5.5s-2.2 5.5-5.6 5.5H17v-11z" fill="#B8F23C" />
    <path d="M5 16h6M3 20h8M5 24h6" stroke="#070C18" strokeWidth="1.8" strokeLinecap="round" opacity=".55" />
    <path d="M32 5l1 2.6 2.6 1-2.6 1-1 2.6-1-2.6-2.6-1 2.6-1z" fill="#FFFFFF" />
  </svg>
);

interface Props {
  /** Light text for dark backgrounds (the header), dark text for light ones. */
  tone?: 'light' | 'dark';
  to?: string;
}

const Brand = ({ tone = 'light', to = '/' }: Props) => (
  <Box component={RouterLink} to={to} aria-label="DSports AI home" sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.1, textDecoration: 'none' }}>
    <LogoMark />
    <Box sx={{ display: 'flex', flexDirection: 'column', lineHeight: 1 }}>
      <Typography component="span" sx={{ fontFamily: '"Sora", sans-serif', fontWeight: 800, fontSize: '1.15rem', letterSpacing: '-0.02em', color: tone === 'light' ? '#fff' : 'text.primary' }}>
        DSports
      </Typography>
      <Typography
        component="span"
        sx={{ fontFamily: '"Sora", sans-serif', fontWeight: 800, fontSize: '0.78rem', letterSpacing: '0.18em', background: gradients.brand, WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', mt: 0.25 }}
      >
        AI ✦
      </Typography>
    </Box>
  </Box>
);

export default Brand;
