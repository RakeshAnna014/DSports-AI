import { Box, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';

export type RangeKey = '15m' | '1h' | '24h' | '7d' | 'custom';
export interface RangeState { key: RangeKey; from: string; to: string }   // from / to: <input type="datetime-local"> values (the viewer's local time)

export const PRESETS: { key: Exclude<RangeKey, 'custom'>; label: string; hours: number }[] = [
  { key: '15m', label: 'Last 15 minutes', hours: 0.25 },
  { key: '1h', label: 'Last hour', hours: 1 },
  { key: '24h', label: 'Last 24 hours', hours: 24 },
  { key: '7d', label: 'Last 7 days', hours: 168 },
];
const MAX_MS = 31 * 24 * 3600 * 1000;

export type RangeQuery = { hours: number } | { since: string; until: string };

/** The query for the service, or the reason the custom range cannot be used (shown to the user; nothing is sent). */
export const rangeQuery = (r: RangeState): { query: RangeQuery; error: null } | { query: null; error: string } => {
  if (r.key !== 'custom') return { query: { hours: PRESETS.find((p) => p.key === r.key)!.hours }, error: null };
  if (!r.from || !r.to) return { query: null, error: 'Choose both a start and an end time.' };
  const from = new Date(r.from).getTime();
  const to = new Date(r.to).getTime();
  if (Number.isNaN(from) || Number.isNaN(to)) return { query: null, error: 'That date and time is not valid.' };
  if (to <= from) return { query: null, error: 'The end must be after the start.' };
  if (to - from > MAX_MS) return { query: null, error: 'The range is limited to 31 days.' };
  return { query: { since: new Date(from).toISOString(), until: new Date(to).toISOString() }, error: null };
};

/** True when the selected window ended in the past: the data is history, not what is happening now. */
export const isHistorical = (r: RangeState, now = Date.now()) => r.key === 'custom' && !!r.to && new Date(r.to).getTime() < now - 5 * 60 * 1000;

export const defaultRange = (): RangeState => ({ key: '24h', from: '', to: '' });

const TimeRangePicker = ({ value, onChange }: { value: RangeState; onChange: (r: RangeState) => void }) => {
  const check = value.key === 'custom' ? rangeQuery(value) : null;
  return (
    <Box data-testid="eng-range">
      <ToggleButtonGroup exclusive size="small" value={value.key} onChange={(_e, k) => k && onChange({ ...value, key: k })} aria-label="time range" sx={{ flexWrap: 'wrap' }}>
        {PRESETS.map((p) => <ToggleButton key={p.key} value={p.key} data-testid={`eng-range-${p.key}`}>{p.label}</ToggleButton>)}
        <ToggleButton value="custom" data-testid="eng-range-custom">Custom range</ToggleButton>
      </ToggleButtonGroup>
      {value.key === 'custom' && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField size="small" type="datetime-local" label="From" value={value.from} onChange={(e) => onChange({ ...value, from: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="datetime-local" label="To" value={value.to} onChange={(e) => onChange({ ...value, to: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          {check?.error && <Typography variant="caption" color="warning.main" sx={{ alignSelf: 'center' }} data-testid="eng-range-error">{check.error}</Typography>}
        </Stack>
      )}
    </Box>
  );
};

export default TimeRangePicker;
