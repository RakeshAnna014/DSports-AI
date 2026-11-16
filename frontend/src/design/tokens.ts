/**
 * NisumSports AI design tokens: the single source of truth for brand colours. The Nisum palette is also exposed as CSS variables
 * (see index.html / `--nisum-*`). `green` is kept only for data and success semantics (charts, savings), never for brand chrome.
 */
export const nisum = {
  navy: '#1B2A41',
  orange: '#E4572E',
  background: '#EEF2F6',
  textSecondary: '#525C6B',
  white: '#FFFFFF',
  blue: '#2376DC',
} as const;

export const colors = {
  navy900: '#131F32',
  navy800: nisum.navy,
  navy700: '#263A55',
  navy600: '#34495F',
  orange: nisum.orange,
  orangeDark: '#C2411C',
  orangeSoft: '#FCEBE5',
  accentOnDark: '#F58A68',
  blue: nisum.blue,
  green: '#16A34A',
  greenDark: '#0F7A38',
  greenSoft: '#E7F8EE',
  surface: nisum.background,
  card: nisum.white,
  ink: nisum.navy,
  muted: nisum.textSecondary,
  line: '#DCE3EC',
} as const;

/** The Nisum template background (white to sky blue to lavender), used instead of dark navy bands. Text on it is navy. */
const templateBg = 'url(/brand/nisum-bg.jpg) center bottom / cover no-repeat, #FFFFFF';

export const gradients = {
  template: templateBg,
  hero: templateBg,
  brand: 'linear-gradient(135deg, #E4572E 0%, #F58A68 100%)',
  ai: 'linear-gradient(135deg, #E4572E 0%, #F58A68 100%)',
  glass: 'linear-gradient(135deg, rgba(255,255,255,0.14), rgba(255,255,255,0.05))',
  section: 'linear-gradient(180deg, #EEF2F6 0%, #FFFFFF 100%)',
  aiPanel: templateBg,
} as const;

export const shadows = {
  card: '0 1px 2px rgba(27,42,65,0.06), 0 8px 24px rgba(27,42,65,0.06)',
  cardHover: '0 2px 4px rgba(27,42,65,0.08), 0 16px 40px rgba(27,42,65,0.14)',
  glow: '0 0 0 1px rgba(228,87,46,0.35), 0 10px 40px rgba(228,87,46,0.30)',
  glass: '0 8px 40px rgba(0,0,0,0.35)',
} as const;

export const motion = {
  fast: '160ms cubic-bezier(.2,.8,.2,1)',
  base: '260ms cubic-bezier(.2,.8,.2,1)',
} as const;
