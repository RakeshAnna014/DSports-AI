/** The shot's coordinates: thousandths of the field's radius from the middle of the pitch, right and up positive. The server refuses a point outside the circle. */
export const FIELD_RADIUS = 1000;

export interface ShotPoint {
  x: number;
  y: number;
}

/**
 * A tap at pixel (px, py) inside a square drawing of the field, `size` pixels across, as a shot. Taps outside the circle are pulled onto the boundary rope (a ball hit
 * over the rope is a boundary), never refused. The result is always a point the server accepts.
 */
export const shotFromTap = (px: number, py: number, size: number): ShotPoint => {
  const half = size / 2;
  let x = ((px - half) / half) * FIELD_RADIUS;
  let y = ((half - py) / half) * FIELD_RADIUS;
  const length = Math.hypot(x, y);
  if (length > FIELD_RADIUS) {
    x = (x / length) * FIELD_RADIUS;
    y = (y / length) * FIELD_RADIUS;
  }
  const clamp = (v: number) => Math.max(-FIELD_RADIUS, Math.min(FIELD_RADIUS, Math.round(v)));
  let rx = clamp(x);
  let ry = clamp(y);
  // Rounding can push a point on the rope a hair outside it; step back in.
  while (rx * rx + ry * ry > FIELD_RADIUS * FIELD_RADIUS) {
    rx -= Math.sign(rx);
    ry -= Math.sign(ry);
  }
  return { x: rx, y: ry };
};

/** Where a shot is drawn inside an SVG whose viewBox is -110..110 on both axes (a little margin round the rope), y pointing down. */
export const toView = (shot: ShotPoint, radius = 100): { cx: number; cy: number } => ({ cx: (shot.x / FIELD_RADIUS) * radius, cy: -(shot.y / FIELD_RADIUS) * radius });
