import { createTheme } from '@mui/material/styles';
import { colors, motion, shadows } from '@/design/tokens';

const theme = createTheme({
  palette: {
    primary: { main: colors.green, light: '#4ADE80', dark: colors.greenDark, contrastText: '#FFFFFF' },
    secondary: { main: colors.orange, light: '#FFA45C', dark: '#C85A00', contrastText: '#FFFFFF' },
    success: { main: colors.green },
    background: { default: colors.surface, paper: colors.card },
    text: { primary: colors.ink, secondary: colors.muted },
    divider: colors.line,
  },
  typography: {
    fontFamily: '"Inter", "Roboto", "Helvetica", "Arial", sans-serif',
    h1: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 800, fontSize: '2.5rem', letterSpacing: '-0.02em' },
    h2: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700, fontSize: '1.9rem', letterSpacing: '-0.015em' },
    h3: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700, fontSize: '1.5rem' },
    h4: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700, fontSize: '1.25rem' },
    h5: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
    h6: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
    button: { textTransform: 'none', fontWeight: 600 },
  },
  shape: { borderRadius: 12 },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: colors.surface },
        '@media (prefers-reduced-motion: reduce)': { '*': { animation: 'none !important', transition: 'none !important' } },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { borderRadius: 10, transition: `transform ${motion.fast}, box-shadow ${motion.fast}, background-color ${motion.fast}`, '&:active': { transform: 'translateY(1px)' } },
        containedPrimary: { '&:hover': { boxShadow: '0 6px 20px rgba(22,163,74,0.35)' } },
        containedSecondary: { '&:hover': { boxShadow: '0 6px 20px rgba(255,122,26,0.40)' } },
      },
    },
    MuiCard: {
      styleOverrides: { root: { borderRadius: 16, boxShadow: shadows.card, transition: `box-shadow ${motion.base}, transform ${motion.base}` } },
    },
    MuiPaper: { styleOverrides: { rounded: { borderRadius: 14 } } },
    MuiChip: { styleOverrides: { root: { fontWeight: 600 } } },
    MuiDrawer: { styleOverrides: { paper: { borderRadius: 0 } } },
    MuiDialog: { styleOverrides: { paper: { borderRadius: 18 } } },
    MuiTooltip: { styleOverrides: { tooltip: { borderRadius: 8 } } },
  },
});

export default theme;
