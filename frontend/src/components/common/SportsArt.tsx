import type { ReactNode } from 'react';

/**
 * Original sports artwork drawn as inline SVG (no external image URLs, no licensing, no network cost, crisp at any size).
 * The project ships no photography; these are the visual layer until real campaign images are supplied.
 */
export const HeroArt = ({ className }: { className?: string }) => (
  <svg className={className} viewBox="0 0 900 560" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false"
    style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
    <defs>
      <linearGradient id="haPitch" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stopColor="#16A34A" stopOpacity="0" />
        <stop offset="1" stopColor="#16A34A" stopOpacity=".38" />
      </linearGradient>
      <radialGradient id="haLight" cx=".5" cy=".5" r=".5">
        <stop offset="0" stopColor="#B8F23C" stopOpacity=".55" />
        <stop offset="1" stopColor="#B8F23C" stopOpacity="0" />
      </radialGradient>
      <linearGradient id="haBat" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stopColor="#F5D7A1" />
        <stop offset="1" stopColor="#C98F4B" />
      </linearGradient>
    </defs>
    {/* stadium floodlights */}
    <circle cx="690" cy="70" r="160" fill="url(#haLight)" />
    <circle cx="820" cy="150" r="110" fill="url(#haLight)" opacity=".7" />
    {/* pitch in perspective */}
    <path d="M0 560V380C220 330 520 330 900 380V560Z" fill="url(#haPitch)" />
    <g stroke="#B8F23C" strokeOpacity=".18" strokeWidth="1.5" fill="none">
      <path d="M450 330L180 560M450 330L330 560M450 330L450 560M450 330L570 560M450 330L720 560" />
      <path d="M0 410C260 372 640 372 900 410M0 470C280 430 620 430 900 470" />
    </g>
    {/* concentric arcs: the stands */}
    <g stroke="#FFFFFF" strokeOpacity=".07" fill="none" strokeWidth="2">
      <circle cx="450" cy="360" r="250" /><circle cx="450" cy="360" r="330" /><circle cx="450" cy="360" r="410" />
    </g>
    {/* bat, ball and stumps */}
    <g transform="translate(620 150) rotate(24)">
      <rect x="0" y="0" width="38" height="190" rx="14" fill="url(#haBat)" />
      <rect x="11" y="-70" width="16" height="80" rx="8" fill="#1F2937" />
      <path d="M19 6v176" stroke="#8A5A24" strokeOpacity=".5" strokeWidth="2" />
    </g>
    <g transform="translate(520 300)">
      <circle r="34" fill="#C0262D" />
      <path d="M-26 -14C-8 -4 -8 4 -26 14M26 -14C8 -4 8 4 26 14" stroke="#F8E7E7" strokeWidth="2.2" fill="none" strokeLinecap="round" />
      <circle r="34" fill="none" stroke="#fff" strokeOpacity=".25" />
    </g>
    <g stroke="#E5E7EB" strokeWidth="5" strokeLinecap="round" opacity=".85">
      <path d="M770 380v-90M788 380v-90M806 380v-90" />
      <path d="M766 288h44" strokeWidth="4" />
    </g>
    {/* a football, a racket and a runner's motion lines for the other sports */}
    <g transform="translate(150 190)" opacity=".9">
      <circle r="46" fill="#F8FAFC" />
      <path d="M0 -18l16 12-6 18h-20l-6-18z" fill="#0B1426" />
      <path d="M0-18V-46M16-6l30-10M10 12l22 28M-10 12L-32 40M-16-6L-46-16" stroke="#0B1426" strokeWidth="3" />
    </g>
    <g stroke="#B8F23C" strokeOpacity=".35" strokeWidth="3" strokeLinecap="round">
      <path d="M40 90h120M70 112h110M20 134h90" />
    </g>
  </svg>
);

const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round' } as const;

const ICONS: Record<string, ReactNode> = {
  cricket: (
    <g {...stroke}>
      <path d="M30 6l8 8-18 22-8-8z" /><path d="M12 28l-6 6" /><circle cx="14" cy="38" r="4" />
    </g>
  ),
  football: (
    <g {...stroke}>
      <circle cx="24" cy="24" r="16" /><path d="M24 15l7 5-3 8h-8l-3-8z" /><path d="M24 15V8M31 20l7-3M28 28l4 7M20 28l-4 7M17 20l-7-3" />
    </g>
  ),
  badminton: (
    <g {...stroke}>
      <ellipse cx="17" cy="17" rx="10" ry="12" transform="rotate(-35 17 17)" /><path d="M24 25l14 14" /><path d="M32 8l6-4M34 12l6 0" />
    </g>
  ),
  tennis: (
    <g {...stroke}>
      <circle cx="24" cy="24" r="16" /><path d="M10 14c8 4 8 16 0 20M38 14c-8 4-8 16 0 20" />
    </g>
  ),
  basketball: (
    <g {...stroke}>
      <circle cx="24" cy="24" r="16" /><path d="M8 24h32M24 8v32M12 12c6 6 6 18 0 24M36 12c-6 6-6 18 0 24" />
    </g>
  ),
  volleyball: (
    <g {...stroke}>
      <circle cx="24" cy="24" r="16" /><path d="M24 8c-4 8-2 14 6 18M10 16c8 0 14 4 16 12M38 18c-6 4-8 10-6 18" />
    </g>
  ),
  running: (
    <g {...stroke}>
      <circle cx="28" cy="9" r="4" /><path d="M20 20l8-4 6 6 6 2M28 16l-4 10 8 6-2 10M24 26l-8 2-4 8" />
    </g>
  ),
  fitness: (
    <g {...stroke}>
      <path d="M8 18v12M14 14v20M34 14v20M40 18v12M14 24h20" />
    </g>
  ),
};

export const SportIcon = ({ sport, size = 44 }: { sport: string; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden="true" focusable="false">
    {ICONS[sport.toLowerCase()] ?? ICONS.fitness}
  </svg>
);

export const SPORT_THEMES: Record<string, { from: string; to: string }> = {
  cricket: { from: '#0F7A38', to: '#B8F23C' },
  football: { from: '#1D4ED8', to: '#38BDF8' },
  badminton: { from: '#7C3AED', to: '#F0ABFC' },
  tennis: { from: '#B45309', to: '#FDE047' },
  basketball: { from: '#C2410C', to: '#FDBA74' },
  volleyball: { from: '#0E7490', to: '#67E8F9' },
  running: { from: '#BE123C', to: '#FDA4AF' },
  fitness: { from: '#334155', to: '#94A3B8' },
};
