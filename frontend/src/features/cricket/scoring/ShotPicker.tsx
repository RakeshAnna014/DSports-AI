import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography, useTheme } from '@mui/material';
import type { MouseEvent } from 'react';
import { shotFromTap, type ShotPoint } from '../shotGeometry';

const SIZE = 280;

/** True for a ball that scored runs off the bat (not a wide, a bye or a leg bye): the only balls a shot can be recorded for. */
export const isBatRunBall = (command: { type: string; delivery?: { kind: string; runs: number; runsType?: string } }): boolean =>
  command.type === 'DELIVER' && command.delivery !== undefined && command.delivery.kind !== 'WIDE' && (command.delivery.runsType ?? 'BAT') === 'BAT' && command.delivery.runs > 0;

/**
 * A drawing of the field to tap where the last ball went. It is optional every time: "Skip" (or closing it) records nothing, and nothing else on the screen waits for it.
 * The pitch is in the middle with the bowling end at the top.
 */
export const ShotPicker = ({ open, runs, onPick, onSkip }: { open: boolean; runs: number; onPick: (shot: ShotPoint) => void; onSkip: () => void }) => {
  const theme = useTheme();
  const tap = (event: MouseEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    onPick(shotFromTap(((event.clientX - box.left) / box.width) * SIZE, ((event.clientY - box.top) / box.height) * SIZE, SIZE));
  };
  return (
    <Dialog open={open} onClose={onSkip} data-testid="shot-picker" fullWidth maxWidth="xs">
      <DialogTitle>Where did it go? ({runs} {runs === 1 ? 'run' : 'runs'})</DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width="100%" style={{ maxWidth: SIZE, touchAction: 'manipulation', cursor: 'crosshair' }} role="img" aria-label="Field. Tap where the ball went. The bowling end is at the top." onClick={tap} data-testid="shot-field">
          <circle cx={SIZE / 2} cy={SIZE / 2} r={SIZE / 2 - 2} fill={theme.palette.success.light} fillOpacity={0.25} stroke={theme.palette.success.main} strokeWidth={2} />
          <circle cx={SIZE / 2} cy={SIZE / 2} r={SIZE / 4} fill="none" stroke={theme.palette.text.disabled} strokeDasharray="4 4" />
          <rect x={SIZE / 2 - 5} y={SIZE / 2 - 28} width={10} height={56} fill={theme.palette.warning.light} />
        </svg>
        <Typography variant="caption" color="text.secondary">Tap the field. This is optional and does not change the score.</Typography>
      </DialogContent>
      <DialogActions><Button onClick={onSkip} data-testid="shot-skip">Skip</Button></DialogActions>
    </Dialog>
  );
};
