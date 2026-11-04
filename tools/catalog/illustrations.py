"""Original product illustrations as SVG (400x400), one drawer per kind of product.

The project ships no photography and no image URLs may be invented, so product visuals are drawn here and written to
`frontend/public/products/<sport>/<slug>-<n>.svg`. They are real static files, validated by `npm run validate:catalog`.
Replacing any file with a licensed photograph (same path) needs no code change.
"""
from __future__ import annotations

import math

W = 400


def _rot(inner: str, deg: float, cx: float = 200, cy: float = 200) -> str:
    return f'<g transform="rotate({deg} {cx} {cy})">{inner}</g>'


def _shade(hex_color: str, factor: float) -> str:
    """Lighten (>1) or darken (<1) a #RRGGBB colour."""
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    def f(v: int) -> int:
        return max(0, min(255, int(v * factor if factor <= 1 else v + (255 - v) * (factor - 1))))
    return f"#{f(r):02X}{f(g):02X}{f(b):02X}"


# ------------------------------------------------------------------------------------------------ drawers (each returns inner SVG)

def bat(c):
    wood = "#E8C48A"
    return _rot(f'''
      <rect x="168" y="86" width="64" height="196" rx="16" fill="url(#wood)"/>
      <rect x="168" y="86" width="64" height="196" rx="16" fill="none" stroke="{_shade(wood, .6)}" stroke-width="2"/>
      <rect x="183" y="130" width="34" height="86" rx="6" fill="{c['main']}" opacity=".92"/>
      <rect x="183" y="226" width="34" height="8" rx="3" fill="{c['accent']}"/>
      <path d="M200 96V270" stroke="{_shade(wood, .7)}" stroke-width="1.6" opacity=".6"/>
      <rect x="190" y="26" width="20" height="70" rx="8" fill="{c['dark']}"/>
      <path d="M190 40h20M190 52h20M190 64h20M190 76h20" stroke="{c['accent']}" stroke-width="3" opacity=".9"/>''', 32)


def ball(c, kind="cricket"):
    if kind == "football":
        return f'''<circle cx="200" cy="200" r="104" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="3"/>
          <path d="M200 148l40 29-15 47h-50l-15-47z" fill="{c['dark']}"/>
          <path d="M200 148v-50M240 177l48-15M225 224l30 40M175 224l-30 40M160 177l-48-15" stroke="{c['dark']}" stroke-width="5"/>
          <path d="M112 162l-8 46M288 162l8 46M145 264l30 22M255 264l-30 22" stroke="{c['accent']}" stroke-width="5" opacity=".8"/>'''
    if kind == "basketball":
        return f'''<circle cx="200" cy="200" r="104" fill="{c['main']}"/>
          <g stroke="#1F2937" stroke-width="5" fill="none"><path d="M96 200h208M200 96v208"/><path d="M128 126c40 36 40 112 0 148M272 126c-40 36-40 112 0 148"/></g>'''
    if kind == "tennis":
        return f'''<circle cx="200" cy="200" r="100" fill="#D6F04A"/>
          <path d="M112 142c58 24 58 92 0 116M288 142c-58 24-58 92 0 116" stroke="#FFFFFF" stroke-width="8" fill="none"/>'''
    if kind == "volleyball":
        return f'''<circle cx="200" cy="200" r="104" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="3"/>
          <path d="M200 96c-40 50-34 110 30 150M100 168c60-8 110 24 124 84M296 150c-52 18-78 70-60 130" stroke="{c['main']}" stroke-width="18" fill="none" opacity=".9"/>
          <path d="M200 96c-40 50-34 110 30 150" stroke="{c['accent']}" stroke-width="8" fill="none"/>'''
    if kind == "tt":
        return f'<circle cx="200" cy="200" r="64" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="4"/><circle cx="182" cy="182" r="12" fill="#F1F5F9"/><text x="200" y="214" font-size="30" text-anchor="middle" fill="{c["main"]}" font-family="Arial" font-weight="700">★★</text>'
    return f'''<circle cx="200" cy="200" r="100" fill="{c['main']}"/>
      <circle cx="200" cy="200" r="100" fill="none" stroke="{_shade(c['main'], .6)}" stroke-width="3"/>
      <path d="M112 150c40 30 40 70 0 100M288 150c-40 30-40 70 0 100" stroke="#FDF2F2" stroke-width="5" fill="none"/>
      <g stroke="#FDF2F2" stroke-width="3" opacity=".9">{''.join(f'<path d="M{104 + i * 3} {160 + i * 18}l22 -5M{296 - i * 3} {160 + i * 18}l-22 -5"/>' for i in range(5))}</g>'''


def shuttlecock(c):
    feathers = "".join(f'<path d="M{200 + dx} 160L{200 + dx * 2.4} 52" stroke="#E2E8F0" stroke-width="10" stroke-linecap="round"/>' for dx in (-30, -15, 0, 15, 30))
    return f'''{_rot(f'<path d="M158 152Q200 120 242 152L276 56Q200 18 124 56Z" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="3"/>{feathers}<circle cx="200" cy="200" r="52" fill="{c["main"]}"/><circle cx="200" cy="200" r="52" fill="none" stroke="{c["dark"]}" stroke-width="3"/>', 25, 200, 200)}
      <path d="M170 150Q200 130 230 150" stroke="{c['accent']}" stroke-width="6" fill="none"/>'''


def shoe(c, kind="sneaker"):
    studs = ""
    if kind == "boot":
        studs = "".join(f'<rect x="{x}" y="282" width="12" height="16" rx="3" fill="{c["dark"]}"/>' for x in (92, 140, 188, 236, 284))
    if kind == "spikes":
        studs = "".join(f'<path d="M{x} 282l7 18l7 -18z" fill="{c["accent"]}"/>' for x in (92, 140, 188, 236, 284))
    return f'''
      <path d="M58 262Q58 244 80 244L152 244Q178 230 218 230L304 238Q348 244 348 264L348 272Q348 282 336 282L74 282Q58 282 58 270Z" fill="{c['dark']}"/>
      <path d="M58 270H348" stroke="{c['accent']}" stroke-width="5"/>
      <path d="M82 244L92 170Q95 148 118 148L152 150Q168 176 204 182Q252 188 304 216Q340 232 344 246Z" fill="{c['main']}"/>
      <path d="M118 148L152 150L160 176L112 170Z" fill="{_shade(c['main'], .75)}"/>
      <path d="M170 186l24 -10M184 198l24 -10M198 210l24 -10M212 220l24 -10" stroke="#FFFFFF" stroke-width="6" stroke-linecap="round"/>
      <path d="M92 226Q190 214 330 242" stroke="{c['accent']}" stroke-width="12" fill="none" stroke-linecap="round"/>
      {studs}'''


def jersey(c):
    return f'''
      <path d="M128 90L172 68Q200 100 228 68L272 90L332 142L296 178L272 158V304H128V158L104 178L68 142Z" fill="{c['main']}"/>
      <path d="M172 68Q200 100 228 68" stroke="{c['accent']}" stroke-width="10" fill="none"/>
      <path d="M128 158V304M272 158V304" stroke="{_shade(c['main'], .75)}" stroke-width="3" opacity=".5"/>
      <rect x="128" y="206" width="144" height="16" fill="{c['accent']}" opacity=".95"/>
      <path d="M68 142L104 178M332 142L296 178" stroke="{c['accent']}" stroke-width="8"/>
      <text x="200" y="262" font-size="40" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".9">DS</text>'''


def shorts(c):
    return f'''
      <path d="M112 100H288L312 296H214L200 196L186 296H88Z" fill="{c['main']}"/>
      <rect x="112" y="100" width="176" height="24" fill="{c['accent']}"/>
      <path d="M200 124V196" stroke="{_shade(c['main'], .7)}" stroke-width="3"/>
      <path d="M134 150L124 280M266 150L276 280" stroke="{c['accent']}" stroke-width="6" opacity=".8"/>
      <path d="M150 100V124M250 100V124" stroke="#FFFFFF" stroke-width="5"/>'''


def socks(c):
    return f'''
      <path d="M146 64H254V212Q254 238 284 252Q330 274 306 312Q282 340 232 322Q166 300 146 244Z" fill="{c['main']}"/>
      <rect x="146" y="64" width="108" height="34" fill="{c['accent']}"/>
      <path d="M146 112H254M146 126H254" stroke="{c['accent']}" stroke-width="5" opacity=".8"/>
      <path d="M254 300Q290 320 312 306" stroke="{c['dark']}" stroke-width="10" fill="none" opacity=".8"/>'''


def gloves(c, kind="batting"):
    fingers = "".join(f'<rect x="{138 + i * 34}" y="{82 + abs(i - 1.5) * 8}" width="30" height="{98 - abs(i - 1.5) * 8}" rx="14" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="2"/>' for i in range(4))
    cuff = f'<rect x="132" y="262" width="144" height="46" rx="10" fill="{c["dark"]}"/><rect x="132" y="276" width="144" height="10" fill="{c["accent"]}"/>'
    thumb = f'<path d="M138 214Q96 196 98 160Q100 140 120 152L152 190Z" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="2"/>'
    palm = f'<rect x="132" y="170" width="144" height="102" rx="22" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="2"/>'
    extra = ""
    if kind == "batting":
        extra = f'<rect x="150" y="190" width="108" height="14" rx="5" fill="{c["accent"]}"/><rect x="150" y="214" width="108" height="14" rx="5" fill="{c["accent"]}" opacity=".7"/>'
    elif kind == "gk":
        extra = f'<path d="M150 196h108M150 214h108M150 232h108" stroke="{c["accent"]}" stroke-width="6" opacity=".9"/>'
    elif kind == "gym":
        extra = f'<path d="M150 214h108" stroke="{c["accent"]}" stroke-width="8"/><rect x="150" y="226" width="108" height="24" rx="8" fill="{_shade(c["dark"], 1.3)}" opacity=".6"/>'
        fingers = "".join(f'<rect x="{138 + i * 34}" y="{120 + abs(i - 1.5) * 6}" width="30" height="{60 - abs(i - 1.5) * 6}" rx="14" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="2"/>' for i in range(4))
    return fingers + thumb + palm + extra + cuff


def helmet(c, kind="cricket"):
    if kind == "cycling":
        vents = "".join(f'<path d="M{150 + i * 30} 130Q{160 + i * 30} 96 {170 + i * 30} 130" stroke="{c["dark"]}" stroke-width="8" fill="none" stroke-linecap="round"/>' for i in range(3))
        return f'''<path d="M82 244Q86 112 200 100Q314 112 318 244Z" fill="{c['main']}"/>
          <path d="M82 244Q86 112 200 100Q314 112 318 244" stroke="{_shade(c['main'], .65)}" stroke-width="3" fill="none"/>
          {vents}
          <path d="M92 244H308" stroke="{c['accent']}" stroke-width="14"/>
          <path d="M150 244Q148 290 176 300M250 244Q252 290 224 300" stroke="{c['dark']}" stroke-width="8" fill="none"/>'''
    grill = "".join(f'<path d="M{104 + i * 26} 214V292" stroke="#CBD5E1" stroke-width="7"/>' for i in range(8)) + "".join(f'<path d="M96 {232 + i * 24}H304" stroke="#CBD5E1" stroke-width="7"/>' for i in range(3))
    return f'''<path d="M88 214Q92 96 200 90Q308 96 312 214Z" fill="{c['main']}"/>
      <path d="M88 214Q92 96 200 90Q308 96 312 214" stroke="{_shade(c['main'], .65)}" stroke-width="3" fill="none"/>
      <rect x="88" y="196" width="224" height="22" rx="6" fill="{c['accent']}"/>
      {grill}
      <path d="M96 214Q96 300 160 308M304 214Q304 300 240 308" stroke="{c['dark']}" stroke-width="10" fill="none"/>'''


def pads(c):
    one = lambda x, r: f'''<g transform="rotate({r} {x} 200)"><rect x="{x - 40}" y="64" width="80" height="272" rx="36" fill="{c['main']}" stroke="{_shade(c['main'], .65)}" stroke-width="3"/>
      <rect x="{x - 26}" y="92" width="52" height="60" rx="14" fill="{c['accent']}" opacity=".9"/>
      <path d="M{x - 40} 188h80M{x - 40} 220h80M{x - 40} 252h80" stroke="{_shade(c['main'], .7)}" stroke-width="4"/>
      <rect x="{x - 40}" y="176" width="80" height="12" fill="{c['dark']}"/><rect x="{x - 40}" y="260" width="80" height="12" fill="{c['dark']}"/></g>'''
    return one(150, -6) + one(256, 6)


def racquet(c, kind="tennis"):
    rx, ry = (62, 84) if kind == "tennis" else (50, 74)
    strings = "".join(f'<path d="M{200 - rx + 6} {128 + i * 14}H{200 + rx - 6}" stroke="#CBD5E1" stroke-width="1.6"/>' for i in range(-3, 6)) + "".join(f'<path d="M{170 + i * 12} {128 - ry + 30}V{128 + ry - 20}" stroke="#CBD5E1" stroke-width="1.6"/>' for i in range(-3, 4))
    return _rot(f'''<ellipse cx="200" cy="128" rx="{rx}" ry="{ry}" fill="#F8FAFC" opacity=".9"/>{strings}
      <ellipse cx="200" cy="128" rx="{rx}" ry="{ry}" fill="none" stroke="{c['main']}" stroke-width="12"/>
      <ellipse cx="200" cy="128" rx="{rx}" ry="{ry}" fill="none" stroke="{c['accent']}" stroke-width="3"/>
      <path d="M188 {128 + ry - 6}L196 226H204L212 {128 + ry - 6}" fill="{c['main']}"/>
      <rect x="190" y="226" width="20" height="96" rx="9" fill="{c['dark']}"/>
      <path d="M190 244h20M190 258h20M190 272h20M190 286h20M190 300h20" stroke="{c['accent']}" stroke-width="3"/>''', 28)


def ttbat(c):
    return _rot(f'''<circle cx="200" cy="140" r="76" fill="{c['main']}"/><circle cx="200" cy="140" r="76" fill="none" stroke="{c['dark']}" stroke-width="8"/>
      <circle cx="200" cy="140" r="54" fill="none" stroke="#FFFFFF" stroke-width="2" opacity=".5"/>
      <rect x="186" y="208" width="28" height="92" rx="9" fill="#D9A55B"/><rect x="186" y="270" width="28" height="30" rx="9" fill="{c['accent']}"/>''', 24)


def bag(c, kind="duffel"):
    if kind == "backpack":
        return f'''<path d="M150 96Q200 40 250 96" stroke="{c['dark']}" stroke-width="12" fill="none"/>
          <rect x="112" y="90" width="176" height="224" rx="48" fill="{c['main']}" stroke="{_shade(c['main'], .65)}" stroke-width="3"/>
          <rect x="140" y="196" width="120" height="86" rx="20" fill="{_shade(c['main'], .8)}"/>
          <path d="M140 226h120" stroke="{c['accent']}" stroke-width="6"/><circle cx="200" cy="244" r="8" fill="{c['accent']}"/>
          <path d="M126 130Q86 200 126 290M274 130Q314 200 274 290" stroke="{c['dark']}" stroke-width="12" fill="none"/>'''
    return f'''<path d="M140 130Q200 50 260 130" stroke="{c['dark']}" stroke-width="12" fill="none"/>
      <rect x="64" y="124" width="272" height="150" rx="46" fill="{c['main']}" stroke="{_shade(c['main'], .65)}" stroke-width="3"/>
      <rect x="64" y="176" width="272" height="14" fill="{c['accent']}"/>
      <path d="M64 190H336" stroke="{_shade(c['main'], .6)}" stroke-width="3"/>
      <rect x="260" y="206" width="62" height="54" rx="14" fill="{_shade(c['main'], .8)}"/>
      <path d="M92 150H236" stroke="{c['dark']}" stroke-width="6" opacity=".7"/>
      <text x="170" y="252" font-size="34" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".92">DSports</text>'''


def bottle(c):
    return f'''<rect x="162" y="62" width="76" height="40" rx="12" fill="{c['dark']}"/><rect x="178" y="40" width="44" height="30" rx="10" fill="{c['accent']}"/>
      <path d="M156 106Q156 96 170 96H230Q244 96 244 106V318Q244 338 224 338H176Q156 338 156 318Z" fill="{c['main']}" opacity=".95"/>
      <rect x="156" y="170" width="88" height="86" fill="#FFFFFF" opacity=".92"/>
      <path d="M170 200h60M170 220h40" stroke="{c['dark']}" stroke-width="6" stroke-linecap="round"/>
      <path d="M170 110V320" stroke="#FFFFFF" stroke-width="6" opacity=".35"/>'''


def belt(c):
    return f'''<path d="M42 232Q200 300 358 232L358 268Q200 338 42 268Z" fill="{c['main']}"/>
      <path d="M42 250Q200 318 358 250" stroke="{c['accent']}" stroke-width="5" fill="none"/>
      <rect x="136" y="204" width="128" height="86" rx="22" fill="{c['dark']}"/>
      <rect x="148" y="214" width="104" height="14" rx="6" fill="{c['accent']}"/>
      <path d="M200 228V282" stroke="#FFFFFF" stroke-width="5" opacity=".5"/><rect x="186" y="236" width="28" height="12" rx="4" fill="#FFFFFF" opacity=".8"/>'''


def watch(c):
    return f'''<path d="M160 36H240L248 120H152Z" fill="{c['dark']}"/><path d="M152 280H248L240 364H160Z" fill="{c['dark']}"/>
      <path d="M168 54H232M168 72H232M168 90H232M168 310H232M168 328H232M168 346H232" stroke="{_shade(c['dark'], 1.5)}" stroke-width="3"/>
      <circle cx="200" cy="200" r="92" fill="{c['main']}"/><circle cx="200" cy="200" r="76" fill="#0B1426"/>
      <circle cx="200" cy="200" r="76" fill="none" stroke="{c['accent']}" stroke-width="5"/>
      <path d="M200 200V148M200 200L238 222" stroke="#FFFFFF" stroke-width="7" stroke-linecap="round"/><circle cx="200" cy="200" r="7" fill="{c['accent']}"/>
      <path d="M140 200h14M246 200h14M200 124v14M200 262v14" stroke="#94A3B8" stroke-width="4"/>
      <text x="200" y="250" font-size="16" font-family="Arial" font-weight="700" text-anchor="middle" fill="{c['accent']}">GPS</text>'''


def dumbbell(c):
    return f'''<rect x="120" y="188" width="160" height="24" rx="8" fill="#CBD5E1"/>
      <rect x="82" y="130" width="52" height="140" rx="12" fill="{c['main']}"/><rect x="60" y="150" width="30" height="100" rx="10" fill="{c['dark']}"/>
      <rect x="266" y="130" width="52" height="140" rx="12" fill="{c['main']}"/><rect x="310" y="150" width="30" height="100" rx="10" fill="{c['dark']}"/>
      <path d="M92 150V250M308 150V250" stroke="{c['accent']}" stroke-width="5"/>'''


def kettlebell(c):
    return f'''<path d="M138 150Q138 62 200 62Q262 62 262 150" stroke="{c['dark']}" stroke-width="24" fill="none"/>
      <circle cx="200" cy="248" r="104" fill="{c['main']}"/><circle cx="200" cy="248" r="104" fill="none" stroke="{_shade(c['main'], .6)}" stroke-width="3"/>
      <ellipse cx="170" cy="214" rx="24" ry="40" fill="#FFFFFF" opacity=".22" transform="rotate(25 170 214)"/>
      <rect x="150" y="238" width="100" height="40" rx="10" fill="{c['accent']}"/><text x="200" y="268" font-size="26" font-family="Arial" font-weight="800" text-anchor="middle" fill="{c['dark']}">12 KG</text>'''


def bands(c):
    ring = lambda cx, cy, rx, ry, col: f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{col}" stroke-width="26"/>'
    return ring(200, 252, 118, 38, c["dark"]) + ring(200, 220, 104, 34, c["main"]) + ring(200, 188, 88, 30, c["accent"]) + ring(200, 158, 70, 24, _shade(c["main"], 1.4))


def mat(c):
    return f'''<path d="M70 150L330 150L350 280L50 280Z" fill="{c['main']}"/>
      <ellipse cx="68" cy="215" rx="40" ry="66" fill="{_shade(c['main'], .8)}"/><ellipse cx="68" cy="215" rx="26" ry="46" fill="{_shade(c['main'], .6)}"/>
      <ellipse cx="68" cy="215" rx="12" ry="24" fill="{c['dark']}"/>
      <path d="M110 190H320M100 220H330M90 250H340" stroke="{c['accent']}" stroke-width="5" opacity=".6"/>'''


def rope(c):
    return f'''<path d="M120 84V190M280 84V190" stroke="{c['dark']}" stroke-width="2"/>
      <rect x="102" y="40" width="36" height="104" rx="14" fill="{c['main']}"/><rect x="262" y="40" width="36" height="104" rx="14" fill="{c['main']}"/>
      <rect x="102" y="40" width="36" height="22" rx="10" fill="{c['accent']}"/><rect x="262" y="40" width="36" height="22" rx="10" fill="{c['accent']}"/>
      <path d="M120 146Q40 330 200 340Q360 330 280 146" stroke="{c['accent']}" stroke-width="9" fill="none" stroke-linecap="round"/>'''


def roller(c):
    ridges = "".join(f'<rect x="{104 + i * 36}" y="120" width="20" height="160" fill="{c["accent"]}"/>' for i in range(7))
    return f'''<rect x="80" y="120" width="240" height="160" rx="30" fill="{c['main']}"/>{ridges}
      <ellipse cx="80" cy="200" rx="26" ry="80" fill="{c['dark']}"/><ellipse cx="80" cy="200" rx="12" ry="40" fill="#0B1426"/>'''


def plates(c):
    return f'''<circle cx="200" cy="200" r="130" fill="{c['dark']}"/><circle cx="200" cy="200" r="112" fill="{c['main']}"/>
      <circle cx="200" cy="200" r="26" fill="#0B1426"/><circle cx="200" cy="200" r="42" fill="none" stroke="{c['accent']}" stroke-width="6"/>
      <path d="M200 88V122M200 278V312M88 200H122M278 200H312" stroke="{c['accent']}" stroke-width="10"/>
      <text x="200" y="168" font-size="30" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".85">10 KG</text>'''


def goggles(c):
    return f'''<path d="M40 190Q200 130 360 190" stroke="{c['dark']}" stroke-width="16" fill="none"/>
      <rect x="62" y="150" width="110" height="94" rx="42" fill="{c['main']}"/><rect x="228" y="150" width="110" height="94" rx="42" fill="{c['main']}"/>
      <rect x="76" y="162" width="82" height="70" rx="32" fill="#7DD3FC" opacity=".85"/><rect x="242" y="162" width="82" height="70" rx="32" fill="#7DD3FC" opacity=".85"/>
      <path d="M172 196Q200 176 228 196" stroke="{c['accent']}" stroke-width="12" fill="none"/>
      <path d="M92 178L124 168M258 178L290 168" stroke="#FFFFFF" stroke-width="6" opacity=".7" stroke-linecap="round"/>'''


def swimcap(c):
    return f'''<path d="M86 240Q86 90 200 90Q314 90 314 240Q314 270 280 272Q200 252 120 272Q86 270 86 240Z" fill="{c['main']}"/>
      <path d="M120 130Q200 70 280 130" stroke="{c['accent']}" stroke-width="10" fill="none"/>
      <path d="M120 272Q200 252 280 272" stroke="{c['dark']}" stroke-width="6" fill="none"/><text x="200" y="196" font-size="40" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".92">DS</text>'''


def swimsuit(c):
    return f'''<path d="M138 60Q150 60 156 100Q200 128 244 100Q250 60 262 60L276 80Q268 150 254 190Q282 240 270 330H130Q118 240 146 190Q132 150 124 80Z" fill="{c['main']}"/>
      <path d="M146 190Q200 214 254 190" stroke="{c['accent']}" stroke-width="10" fill="none"/>
      <path d="M130 330Q200 300 270 330" stroke="{c['accent']}" stroke-width="8" fill="none"/>
      <path d="M156 100Q200 128 244 100" stroke="{c['dark']}" stroke-width="6" fill="none" opacity=".6"/>'''


def kickboard(c):
    return f'''<path d="M140 50H260Q300 50 300 100V290Q300 350 250 350H150Q100 350 100 290V100Q100 50 140 50Z" fill="{c['main']}"/>
      <ellipse cx="160" cy="214" rx="26" ry="40" fill="#F8FAFC"/><ellipse cx="240" cy="214" rx="26" ry="40" fill="#F8FAFC"/>
      <path d="M130 100H270" stroke="{c['accent']}" stroke-width="12"/><path d="M130 304H270" stroke="{c['dark']}" stroke-width="12"/>'''


def buoy(c):
    return f'''<ellipse cx="200" cy="130" rx="92" ry="66" fill="{c['main']}"/><ellipse cx="200" cy="270" rx="92" ry="66" fill="{c['accent']}"/>
      <rect x="150" y="150" width="100" height="100" fill="{c['dark']}" opacity=".85"/>
      <path d="M180 130h40M180 270h40" stroke="#FFFFFF" stroke-width="8" opacity=".7"/>'''


def fins(c):
    return _rot(f'''<path d="M150 70Q150 40 200 40Q250 40 250 70L270 280Q272 340 200 340Q128 340 130 280Z" fill="{c['main']}"/>
      <path d="M170 200L180 330M230 200L220 330M200 200V338" stroke="{c['accent']}" stroke-width="6" opacity=".9"/>
      <path d="M152 74Q200 112 248 74V112Q200 150 152 112Z" fill="{c['dark']}"/>''', 10)


def waterproofbag(c):
    return f'''<path d="M120 60H280L270 110H130Z" fill="{c['dark']}"/><path d="M130 110H270Q312 130 312 230Q312 340 200 340Q88 340 88 230Q88 130 130 110Z" fill="{c['main']}"/>
      <path d="M130 70V110M170 70V110M230 70V110M270 70V110" stroke="{c['accent']}" stroke-width="6"/>
      <path d="M150 150Q140 230 160 300" stroke="#FFFFFF" stroke-width="8" opacity=".25" fill="none"/>
      <text x="200" y="240" font-size="30" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".9">DRY</text>'''


def bike(c, kind="road"):
    wheel = lambda cx: f'<circle cx="{cx}" cy="270" r="84" fill="none" stroke="#1F2937" stroke-width="12"/><circle cx="{cx}" cy="270" r="72" fill="none" stroke="#94A3B8" stroke-width="2"/><circle cx="{cx}" cy="270" r="8" fill="#1F2937"/>' + "".join(f'<path d="M{cx} 270L{cx + 72 * math.cos(a * math.pi / 6):.1f} {270 + 72 * math.sin(a * math.pi / 6):.1f}" stroke="#94A3B8" stroke-width="1.5"/>' for a in range(12))
    fat = '<circle cx="86" cy="270" r="88" fill="none" stroke="#475569" stroke-width="6"/>' if kind == "mtb" else ""
    return f'''{wheel(86)}{wheel(314)}{fat}
      <path d="M86 270L156 130H250L314 270M156 130L200 270H86M250 130L200 270" stroke="{c['main']}" stroke-width="12" fill="none" stroke-linejoin="round" stroke-linecap="round"/>
      <path d="M156 130L146 100H182" stroke="{c['dark']}" stroke-width="10" fill="none" stroke-linecap="round"/>
      <path d="M250 130L264 96H296Q312 100 306 118" stroke="{c['dark']}" stroke-width="10" fill="none" stroke-linecap="round"/>
      <circle cx="200" cy="270" r="16" fill="{c['accent']}"/><path d="M200 270L186 300M200 270L214 240" stroke="{c['dark']}" stroke-width="8" stroke-linecap="round"/>'''


def bikelight(c):
    return f'''<rect x="130" y="130" width="120" height="100" rx="26" fill="{c['main']}"/><rect x="226" y="146" width="40" height="68" rx="14" fill="{c['dark']}"/>
      <circle cx="170" cy="180" r="30" fill="#FDE68A"/><circle cx="170" cy="180" r="18" fill="#FFFFFF"/>
      <path d="M270 150L350 118M270 180H352M270 210L350 242" stroke="#FDE68A" stroke-width="8" stroke-linecap="round" opacity=".8"/>
      <rect x="120" y="226" width="140" height="22" rx="8" fill="{c['accent']}"/>'''


def glasses(c):
    return f'''<path d="M44 168Q200 140 356 168" stroke="{c['dark']}" stroke-width="12" fill="none"/>
      <path d="M72 168H178Q186 236 138 244Q84 244 72 168Z" fill="{c['main']}" opacity=".9"/><path d="M222 168H328Q316 244 262 244Q214 236 222 168Z" fill="{c['main']}" opacity=".9"/>
      <path d="M96 182H164M246 182H306" stroke="#FFFFFF" stroke-width="6" opacity=".5" stroke-linecap="round"/>
      <path d="M178 176Q200 160 222 176" stroke="{c['accent']}" stroke-width="10" fill="none"/>'''


def saddlebag(c):
    return f'''<path d="M70 150Q200 110 330 150L310 280Q200 316 90 280Z" fill="{c['main']}"/><path d="M70 150Q200 110 330 150" stroke="{c['dark']}" stroke-width="8" fill="none"/>
      <path d="M96 206Q200 238 304 206" stroke="{c['accent']}" stroke-width="9" fill="none"/><rect x="176" y="118" width="48" height="26" rx="8" fill="{c['dark']}"/><circle cx="200" cy="236" r="10" fill="{c['accent']}"/>'''


def net(c):
    grid = "".join(f'<path d="M90 {110 + i * 20}H310" stroke="#E2E8F0" stroke-width="2"/>' for i in range(9)) + "".join(f'<path d="M{90 + i * 20} 100V270" stroke="#E2E8F0" stroke-width="2"/>' for i in range(12))
    return f'''<rect x="90" y="100" width="220" height="170" fill="#0B1426" opacity=".25"/>{grid}
      <rect x="90" y="100" width="220" height="14" fill="{c['accent']}"/><path d="M70 90V320M330 90V320" stroke="{c['dark']}" stroke-width="12" stroke-linecap="round"/>
      <path d="M90 270H310" stroke="{c['main']}" stroke-width="8"/>'''


def cones(c):
    one = lambda x, y, s: f'<path d="M{x} {y - 90 * s}L{x + 52 * s} {y}H{x - 52 * s}Z" fill="{c["main"]}"/><rect x="{x - 66 * s}" y="{y}" width="{132 * s}" height="{14 * s}" rx="{5 * s}" fill="{c["dark"]}"/><path d="M{x - 26 * s} {y - 40 * s}H{x + 26 * s}" stroke="#FFFFFF" stroke-width="{9 * s}"/>'
    return one(110, 290, 1) + one(200, 250, .86) + one(290, 290, 1)


def goal(c):
    grid = "".join(f'<path d="M96 {112 + i * 18}H304" stroke="#94A3B8" stroke-width="2"/>' for i in range(8)) + "".join(f'<path d="M{96 + i * 17} 104V270" stroke="#94A3B8" stroke-width="2"/>' for i in range(13))
    return f'''<path d="M70 290V100H330V290" stroke="#64748B" stroke-width="14" fill="none" stroke-linejoin="round"/>
      <path d="M70 100L110 70H290L330 100" stroke="#CBD5E1" stroke-width="9" fill="none"/>{grid}
      <path d="M70 290H330" stroke="{c['main']}" stroke-width="10"/>'''


def tttable(c):
    return f'''<path d="M70 170L330 170L370 250H30Z" fill="{c['main']}"/><path d="M200 170V250" stroke="#FFFFFF" stroke-width="4"/><path d="M50 210H350" stroke="#FFFFFF" stroke-width="4"/>
      <path d="M30 250H370V268H30Z" fill="{c['dark']}"/><path d="M60 268V340M340 268V340M110 268V320M290 268V320" stroke="{c['dark']}" stroke-width="12" stroke-linecap="round"/>
      <path d="M200 120V176" stroke="#E2E8F0" stroke-width="3"/><path d="M120 128H280V170H120Z" fill="#E2E8F0" opacity=".55"/><path d="M120 128H280" stroke="#FFFFFF" stroke-width="6"/>'''


def case(c):
    return f'''<path d="M110 90Q200 54 290 90L300 290Q200 340 100 290Z" fill="{c['main']}" stroke="{_shade(c['main'], .65)}" stroke-width="3"/>
      <path d="M130 120Q200 96 270 120" stroke="{c['accent']}" stroke-width="8" fill="none"/><path d="M120 150Q200 130 280 150" stroke="{c['dark']}" stroke-width="5" fill="none"/>
      <circle cx="200" cy="224" r="46" fill="{c['dark']}"/><circle cx="200" cy="224" r="32" fill="none" stroke="{c['accent']}" stroke-width="5"/>'''


def support(c, kind="ankle"):
    if kind == "knee":
        pad = lambda x: f'<path d="M{x - 56} 90H{x + 56}L{x + 66} 300Q{x} 330 {x - 66} 300Z" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="3"/><ellipse cx="{x}" cy="196" rx="40" ry="46" fill="{c["accent"]}" opacity=".9"/><path d="M{x - 56} 120H{x + 56}M{x - 60} 270H{x + 60}" stroke="{c["dark"]}" stroke-width="7"/>'
        return pad(130) + pad(272)
    return f'''<path d="M120 70H280L290 190L330 270Q340 320 280 328H150Q96 328 96 280L112 190Z" fill="{c['main']}"/>
      <path d="M112 190H290" stroke="{c['accent']}" stroke-width="10"/><path d="M130 120H270M130 150H270" stroke="{c['dark']}" stroke-width="5" opacity=".6"/>
      <path d="M96 280Q200 250 330 270" stroke="{c['dark']}" stroke-width="10" fill="none"/>'''


def guard(c, kind="shin"):
    if kind == "arm":
        return _rot(f'<path d="M120 90H280L296 310H104Z" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="3"/><rect x="146" y="120" width="108" height="168" rx="24" fill="{c["accent"]}" opacity=".85"/><path d="M100 150H300M100 244H300" stroke="{c["dark"]}" stroke-width="12"/>', 8)
    if kind == "thigh":
        return f'<path d="M150 60H250Q300 130 290 330Q200 350 110 330Q100 130 150 60Z" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="3"/><path d="M168 100H232Q262 160 256 300Q200 312 144 300Q138 160 168 100Z" fill="{c["accent"]}" opacity=".85"/><path d="M110 150H290M104 250H296" stroke="{c["dark"]}" stroke-width="12"/>'
    one = lambda x, r: f'<g transform="rotate({r} {x} 200)"><path d="M{x - 44} 66Q{x} 40 {x + 44} 66L{x + 54} 330Q{x} 350 {x - 54} 330Z" fill="{c["main"]}" stroke="{_shade(c["main"], .65)}" stroke-width="3"/><path d="M{x - 24} 100Q{x} 90 {x + 24} 100L{x + 28} 296Q{x} 306 {x - 28} 296Z" fill="{c["accent"]}" opacity=".85"/><rect x="{x - 54}" y="150" width="108" height="14" fill="{c["dark"]}"/><rect x="{x - 54}" y="250" width="108" height="14" fill="{c["dark"]}"/></g>'
    return one(140, -5) + one(262, 5)


def cap(c):
    return f'''<path d="M96 240Q96 100 220 100Q320 104 322 240Z" fill="{c['main']}"/><path d="M80 244H340Q352 290 280 292Q150 292 80 244Z" fill="{c['dark']}"/>
      <path d="M220 100V240" stroke="{_shade(c['main'], .7)}" stroke-width="3"/><circle cx="220" cy="98" r="9" fill="{c['accent']}"/><text x="214" y="200" font-size="34" font-family="Arial" font-weight="800" text-anchor="middle" fill="#FFFFFF" opacity=".92">DS</text>'''


def wristband(c):
    return f'''<ellipse cx="200" cy="200" rx="116" ry="64" fill="none" stroke="{c['main']}" stroke-width="64"/>
      <ellipse cx="200" cy="200" rx="116" ry="64" fill="none" stroke="{c['accent']}" stroke-width="10"/><ellipse cx="200" cy="200" rx="84" ry="40" fill="none" stroke="#FFFFFF" stroke-width="3" opacity=".4"/>'''


def grip(c):
    return f'''<rect x="90" y="150" width="220" height="100" rx="22" fill="{c['main']}"/><ellipse cx="90" cy="200" rx="30" ry="50" fill="{_shade(c['main'], .7)}"/><ellipse cx="90" cy="200" rx="14" ry="26" fill="{c['dark']}"/>
      <path d="M130 150V250M170 150V250M210 150V250M250 150V250M290 150V250" stroke="{c['accent']}" stroke-width="5" opacity=".7"/><rect x="170" y="178" width="100" height="44" rx="10" fill="#FFFFFF" opacity=".9"/>
      <text x="220" y="208" font-size="22" font-family="Arial" font-weight="800" text-anchor="middle" fill="{c['dark']}">GRIP</text>'''


def kit(c):
    return gloves(c, "batting") + ""


DRAWERS = {
    "bat": bat, "ball-cricket": lambda c: ball(c, "cricket"), "ball-football": lambda c: ball(c, "football"), "ball-basketball": lambda c: ball(c, "basketball"),
    "ball-tennis": lambda c: ball(c, "tennis"), "ball-volleyball": lambda c: ball(c, "volleyball"), "ball-tt": lambda c: ball(c, "tt"), "shuttlecock": shuttlecock,
    "shoe": lambda c: shoe(c, "sneaker"), "boot": lambda c: shoe(c, "boot"), "spikes": lambda c: shoe(c, "spikes"),
    "jersey": jersey, "shorts": shorts, "socks": socks,
    "gloves-batting": lambda c: gloves(c, "batting"), "gloves-gk": lambda c: gloves(c, "gk"), "gloves-gym": lambda c: gloves(c, "gym"), "gloves-cycling": lambda c: gloves(c, "gym"),
    "helmet": lambda c: helmet(c, "cricket"), "helmet-cycling": lambda c: helmet(c, "cycling"), "pads": pads,
    "racquet-tennis": lambda c: racquet(c, "tennis"), "racquet-badminton": lambda c: racquet(c, "badminton"), "ttbat": ttbat,
    "bag": lambda c: bag(c, "duffel"), "backpack": lambda c: bag(c, "backpack"), "bottle": bottle, "belt": belt, "watch": watch,
    "dumbbell": dumbbell, "kettlebell": kettlebell, "bands": bands, "mat": mat, "rope": rope, "roller": roller, "plates": plates,
    "goggles": goggles, "swimcap": swimcap, "swimsuit": swimsuit, "kickboard": kickboard, "buoy": buoy, "fins": fins, "waterproofbag": waterproofbag,
    "bike-road": lambda c: bike(c, "road"), "bike-mtb": lambda c: bike(c, "mtb"), "bikelight": bikelight, "glasses": glasses, "saddlebag": saddlebag,
    "net": net, "cones": cones, "goal": goal, "tttable": tttable, "case": case,
    "support-ankle": lambda c: support(c, "ankle"), "support-knee": lambda c: support(c, "knee"),
    "guard-shin": lambda c: guard(c, "shin"), "guard-arm": lambda c: guard(c, "arm"), "guard-thigh": lambda c: guard(c, "thigh"),
    "cap": cap, "wristband": wristband, "grip": grip, "kit": kit,
}


# ------------------------------------------------------------------------------------------------ frames

def _defs(bg_a: str, bg_b: str) -> str:
    return f'''<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{bg_a}"/><stop offset="1" stop-color="{bg_b}"/></linearGradient>
  <linearGradient id="wood" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#F2D9A8"/><stop offset=".5" stop-color="#E4BE80"/><stop offset="1" stop-color="#C99A58"/></linearGradient>
  <radialGradient id="spot" cx=".5" cy=".42" r=".6"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".95"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>
</defs>'''


def render(kind: str, colors: dict, *, view: int = 1, label: str = "") -> str:
    """One product image. view 1 = front on a light studio backdrop, 2 = angled on a tinted backdrop, 3 = detail (zoomed)."""
    inner = DRAWERS[kind](colors)
    tints = {1: ("#F6F8FC", "#E8EEF7"), 2: (_shade(colors["main"], 1.88), _shade(colors["accent"], 1.8)), 3: ("#EEF2F8", "#DCE5F2")}
    a, b = tints.get(view, tints[1])
    if view == 1:
        body = f'<g transform="translate(200 200) scale(.82) translate(-200 -200)">{inner}</g>'
    elif view == 2:
        body = f'<g transform="translate(200 206) rotate(-9) scale(.78) translate(-200 -200)">{inner}</g>'
    else:
        body = f'<g transform="translate(200 214) scale(1.5) translate(-200 -200)">{inner}</g>'
    shadow = '<ellipse cx="200" cy="338" rx="110" ry="14" fill="#0B1426" opacity=".12"/>' if view != 3 else ""
    badge = f'<g><rect x="14" y="14" width="86" height="26" rx="13" fill="#0B1426" opacity=".78"/><text x="57" y="32" font-size="12" font-family="Arial" font-weight="700" text-anchor="middle" fill="#FFFFFF">{label}</text></g>' if label else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {W}" width="{W}" height="{W}" role="img" aria-label="product illustration">'
            f'{_defs(a, b)}<rect width="{W}" height="{W}" fill="url(#bg)"/><rect width="{W}" height="{W}" fill="url(#spot)"/>{shadow}{body}{badge}</svg>')
