/** DSports AI design tokens: one place for the premium sports-tech look (dark navy hero, vibrant green, lime highlight, orange for AI). */
export const colors = {
  navy900: '#070C18',
  navy800: '#0B1426',
  navy700: '#101D38',
  navy600: '#18284A',
  green: '#16A34A',
  greenDark: '#0F7A38',
  greenSoft: '#E7F8EE',
  lime: '#B8F23C',
  orange: '#FF7A1A',
  orangeSoft: '#FFF1E6',
  surface: '#F3F6FB',
  card: '#FFFFFF',
  ink: '#0B1426',
  muted: '#5B677F',
  line: '#E3E8F2',
} as const;

export const gradients = {
  hero:
    'radial-gradient(900px 520px at 85% -10%, rgba(22,163,74,0.38), transparent 60%),' +
    'radial-gradient(700px 420px at -5% 105%, rgba(255,122,26,0.20), transparent 60%),' +
    'linear-gradient(160deg, #070C18 0%, #0B1A2E 55%, #0B2A22 100%)',
  brand: 'linear-gradient(135deg, #16A34A 0%, #B8F23C 100%)',
  ai: 'linear-gradient(135deg, #FF7A1A 0%, #FFB020 100%)',
  glass: 'linear-gradient(135deg, rgba(255,255,255,0.14), rgba(255,255,255,0.05))',
  section: 'linear-gradient(180deg, #F3F6FB 0%, #FFFFFF 100%)',
  aiPanel: 'linear-gradient(135deg, #0B1426 0%, #12304A 60%, #0F4A33 100%)',
} as const;

export const shadows = {
  card: '0 1px 2px rgba(11,20,38,0.06), 0 8px 24px rgba(11,20,38,0.06)',
  cardHover: '0 2px 4px rgba(11,20,38,0.08), 0 16px 40px rgba(11,20,38,0.14)',
  glow: '0 0 0 1px rgba(184,242,60,0.35), 0 10px 40px rgba(22,163,74,0.35)',
  glass: '0 8px 40px rgba(0,0,0,0.35)',
} as const;

export const motion = {
  fast: '160ms cubic-bezier(.2,.8,.2,1)',
  base: '260ms cubic-bezier(.2,.8,.2,1)',
} as const;
