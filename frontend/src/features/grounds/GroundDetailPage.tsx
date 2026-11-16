import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { groundsApi } from '@/api/grounds';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { clock, localToday, nextSelection, selectionSummary, when } from '@/lib/groundSlots';
import type { Slot } from '@/types/grounds';

const REASON: Record<string, string> = { BOOKED: 'Booked', BLACKED_OUT: 'Closed', TOO_SOON: 'Too soon', TOO_FAR: 'Not open yet' };

/** One ground: pick a day, pick a run of consecutive slots, then hold it and go and pay. The price shown is the server's; the server re-prices on hold. */
const GroundDetailPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const date = searchParams.get('date') || localToday();
  const [selected, setSelected] = useState<number[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const attemptKey = useRef<string | null>(null);

  const ground = useQuery({ queryKey: ['ground', id], queryFn: () => groundsApi.get(id) });
  const slots = useQuery({ queryKey: ['ground-slots', id, date], queryFn: () => groundsApi.slots(id, date) });
  useEffect(() => { setSelected([]); attemptKey.current = null; }, [date, id]);

  const timeZone = ground.data?.timeZone ?? 'Asia/Kolkata';
  const summary = slots.data ? selectionSummary(slots.data, selected) : undefined;

  const holdMutation = useMutation({
    mutationFn: () => {
      if (!summary) throw new Error('Choose a slot first');
      // The same key for the same choice: a double click or a retry returns the same booking.
      attemptKey.current ??= crypto.randomUUID();
      return groundsApi.hold({ groundId: id, startAt: summary.start, endAt: summary.end }, attemptKey.current);
    },
    onSuccess: (booking) => navigate(`/bookings/${booking.id}/pay`),
    onError: (err) => {
      attemptKey.current = null;
      const code = getApiError(err)?.code;
      if (code === 'SLOT_UNAVAILABLE') {
        setMessage('Someone just took that time. The list has been refreshed: please choose another slot.');
        setSelected([]);
        void queryClient.invalidateQueries({ queryKey: ['ground-slots', id, date] });
      } else if (code === 'HOLD_LIMIT_REACHED') {
        setMessage('You already have unpaid slots on hold. Pay for them or let them expire (a few minutes) before holding another.');
      } else {
        setMessage(getApiErrorMessage(err, 'That slot could not be held.'));
      }
    },
  });

  const choose = (list: Slot[], index: number) => { setMessage(null); attemptKey.current = null; setSelected((s) => nextSelection(list, s, index)); };

  if (ground.isError) return <ErrorState body="We couldn't find that ground." onRetry={() => void ground.refetch()} />;
  const g = ground.data;
  return (
    <Box>
      {g ? (
        <>
          <Typography variant="h3" component="h1">{g.name}</Typography>
          <Typography variant="body2" color="text.secondary">{g.address}, {g.city}</Typography>
          <Stack direction="row" spacing={0.75} sx={{ my: 1.5 }} flexWrap="wrap" useFlexGap>
            <Chip size="small" label={g.pitchType.replace('_', ' ').toLowerCase()} />
            <Chip size="small" variant="outlined" label={`${g.slotMinutes} min slots`} />
            {g.facilities.map((f) => <Chip key={f} size="small" variant="outlined" label={f} />)}
          </Stack>
        </>
      ) : <Skeleton variant="text" height={48} width={280} />}

      <TextField size="small" label="Date" type="date" value={date} onChange={(e) => setSearchParams({ date: e.target.value })} InputLabelProps={{ shrink: true }} sx={{ my: 1 }} />
      {slots.isError && <ErrorState body="We couldn't load the slots." onRetry={() => void slots.refetch()} />}
      {slots.data && (
        <Box sx={{ display: 'grid', gap: 1, gridTemplateColumns: { xs: 'repeat(2, 1fr)', sm: 'repeat(4, 1fr)', md: 'repeat(6, 1fr)' }, mt: 1 }} data-testid="slot-grid">
          {slots.data.map((slot, index) => (
            <Button key={slot.start} variant={selected.includes(index) ? 'contained' : 'outlined'} disabled={!slot.available} onClick={() => choose(slots.data!, index)}
              aria-pressed={selected.includes(index)} sx={{ flexDirection: 'column', py: 0.75 }} data-testid="slot">
              <Typography variant="body2" fontWeight={700}>{clock(slot.start, timeZone)}–{clock(slot.end, timeZone)}</Typography>
              <Typography variant="caption">{slot.available ? formatPrice(slot.price, g?.currency) : REASON[slot.unavailableReason ?? ''] ?? 'Unavailable'}</Typography>
            </Button>
          ))}
        </Box>
      )}
      {slots.data && slots.data.length === 0 && <Alert severity="info" sx={{ mt: 1 }}>This ground has no slots that day.</Alert>}

      {message && <Alert severity="warning" sx={{ mt: 2 }} data-testid="hold-message">{message}</Alert>}
      <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
        {summary ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
            <Box>
              <Typography variant="subtitle1" fontWeight={800} data-testid="selection-summary">{when(summary.start, timeZone)} – {clock(summary.end, timeZone)} · {formatPrice(summary.price, g?.currency)}</Typography>
              <Typography variant="caption" color="text.secondary">{summary.count} slot{summary.count === 1 ? '' : 's'}. We hold it for you while you pay.</Typography>
            </Box>
            {isAuthenticated ? (
              <Button variant="contained" disabled={holdMutation.isPending} onClick={() => holdMutation.mutate()} data-testid="hold-and-pay">Hold and pay</Button>
            ) : (
              <Button variant="contained" onClick={() => navigate('/login')}>Sign in to book</Button>
            )}
          </Stack>
        ) : <Typography variant="body2" color="text.secondary">Choose one or more consecutive slots.</Typography>}
      </Paper>
    </Box>
  );
};

export default GroundDetailPage;
