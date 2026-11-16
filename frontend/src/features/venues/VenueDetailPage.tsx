import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Paper, Skeleton, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { venuesApi } from '@/api/venues';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { clock, localToday, nextSelection, selectionSummary, when } from '@/lib/venueSlots';
import type { Slot } from '@/types/venues';

const REASON: Record<string, string> = { BOOKED: 'Booked', BLACKED_OUT: 'Closed', TOO_SOON: 'Too soon', TOO_FAR: 'Not open yet', NOT_ACCEPTING: 'Unavailable' };

/**
 * One venue: pick a court, the sport you will play on it, a day, then a run of consecutive slots, and hold it to go and pay. The price shown is the
 * server's; the server re-prices on hold. Courts are independent: the same time on two courts can be booked by two people.
 */
const VenueDetailPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const date = searchParams.get('date') || localToday();
  const sportSlug = searchParams.get('sport');
  const [courtId, setCourtId] = useState<string | null>(null);
  const [sportId, setSportId] = useState<string | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const attemptKey = useRef<string | null>(null);

  const venue = useQuery({ queryKey: ['venue', id], queryFn: () => venuesApi.get(id) });
  const courts = venue.data?.courts ?? [];
  const court = courts.find((c) => c.id === courtId) ?? courts.find((c) => c.sports.some((s) => s.slug === sportSlug)) ?? courts[0];
  const sport = court?.sports.find((s) => s.id === sportId) ?? court?.sports.find((s) => s.slug === sportSlug) ?? court?.sports[0];
  const slots = useQuery({ queryKey: ['court-slots', id, court?.id, date], queryFn: () => venuesApi.slots(id, court!.id, date), enabled: !!court });
  useEffect(() => { setSelected([]); attemptKey.current = null; }, [date, id, court?.id, sport?.id]);

  const timeZone = venue.data?.venue.timeZone ?? 'Asia/Kolkata';
  const currency = venue.data?.venue.currency;
  const summary = slots.data ? selectionSummary(slots.data, selected) : undefined;

  const holdMutation = useMutation({
    mutationFn: () => {
      if (!summary || !court || !sport) throw new Error('Choose a court, a sport and a slot first');
      // The same key for the same choice: a double click or a retry returns the same booking.
      attemptKey.current ??= crypto.randomUUID();
      return venuesApi.hold({ courtId: court.id, sportId: sport.id, startAt: summary.start, endAt: summary.end }, attemptKey.current);
    },
    onSuccess: (booking) => navigate(`/bookings/${booking.id}/pay`),
    onError: (err) => {
      attemptKey.current = null;
      const code = getApiError(err)?.code;
      if (code === 'SLOT_UNAVAILABLE') {
        setMessage('Someone just took that time. The list has been refreshed: please choose another slot.');
        setSelected([]);
        void queryClient.invalidateQueries({ queryKey: ['court-slots', id, court?.id, date] });
      } else if (code === 'HOLD_LIMIT_REACHED') {
        setMessage('You already have unpaid slots on hold. Pay for them or let them expire (a few minutes) before holding another.');
      } else if (code === 'VENUE_NOT_ACCEPTING_BOOKINGS') {
        setMessage('This venue is not taking new bookings right now.');
        void queryClient.invalidateQueries({ queryKey: ['venue', id] });
      } else if (code === 'SPORT_NOT_SUPPORTED' || code === 'SPORT_NOT_BOOKABLE') {
        setMessage('That sport cannot be booked on this court any more. Please choose another.');
        void queryClient.invalidateQueries({ queryKey: ['venue', id] });
      } else {
        setMessage(getApiErrorMessage(err, 'That slot could not be held.'));
      }
    },
  });

  const choose = (list: Slot[], index: number) => { setMessage(null); attemptKey.current = null; setSelected((s) => nextSelection(list, s, index)); };

  if (venue.isError) return <ErrorState body="We couldn't find that venue." onRetry={() => void venue.refetch()} />;
  const v = venue.data?.venue;
  return (
    <Box>
      {v ? (
        <>
          <Typography variant="h3" component="h1">{v.name}</Typography>
          <Typography variant="body2" color="text.secondary">{v.address}, {v.city}</Typography>
          <Stack direction="row" spacing={0.75} sx={{ my: 1.5 }} flexWrap="wrap" useFlexGap>
            {v.facilities.map((f) => <Chip key={f} size="small" variant="outlined" label={f} />)}
          </Stack>
          {venue.data && !venue.data.acceptingBookings && (
            <Alert severity="warning" sx={{ mb: 2 }} data-testid="not-accepting">This venue is not taking new bookings right now. Existing bookings are not affected.</Alert>
          )}
        </>
      ) : <Skeleton variant="text" height={48} width={280} />}

      {courts.length > 0 && (
        <>
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>Court</Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
            {courts.map((c) => (
              <Chip key={c.id} label={c.surface ? `${c.name} · ${c.surface}` : c.name} color={c.id === court?.id ? 'primary' : 'default'} variant={c.id === court?.id ? 'filled' : 'outlined'}
                onClick={() => { setCourtId(c.id); setSportId(null); }} data-testid="court-chip" aria-pressed={c.id === court?.id} />
            ))}
          </Stack>
        </>
      )}
      {court && (
        <>
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>Sport on this court</Typography>
          <ToggleButtonGroup exclusive size="small" value={sport?.id ?? null} onChange={(_, value: string | null) => { if (value) setSportId(value); }} sx={{ mb: 1.5, flexWrap: 'wrap' }} aria-label="Sport on this court">
            {court.sports.map((s) => <ToggleButton key={s.id} value={s.id} data-testid="court-sport">{s.name}</ToggleButton>)}
          </ToggleButtonGroup>
        </>
      )}
      {venue.data && courts.length === 0 && <Alert severity="info" sx={{ mb: 2 }}>This venue has no courts open for booking yet.</Alert>}

      <Box>
        <TextField size="small" label="Date" type="date" value={date} onChange={(e) => setSearchParams({ ...(sportSlug ? { sport: sportSlug } : {}), date: e.target.value })}
          InputLabelProps={{ shrink: true }} sx={{ my: 1 }} />
      </Box>
      {slots.isError && <ErrorState body="We couldn't load the slots." onRetry={() => void slots.refetch()} />}
      {slots.data && (
        <Box sx={{ display: 'grid', gap: 1, gridTemplateColumns: { xs: 'repeat(2, 1fr)', sm: 'repeat(4, 1fr)', md: 'repeat(6, 1fr)' }, mt: 1 }} data-testid="slot-grid">
          {slots.data.map((slot, index) => (
            <Button key={slot.start} variant={selected.includes(index) ? 'contained' : 'outlined'} disabled={!slot.available || !venue.data?.acceptingBookings} onClick={() => choose(slots.data!, index)}
              aria-pressed={selected.includes(index)} sx={{ flexDirection: 'column', py: 0.75 }} data-testid="slot">
              <Typography variant="body2" fontWeight={700}>{clock(slot.start, timeZone)}–{clock(slot.end, timeZone)}</Typography>
              <Typography variant="caption">{slot.available ? formatPrice(slot.price, currency) : REASON[slot.unavailableReason ?? ''] ?? 'Unavailable'}</Typography>
            </Button>
          ))}
        </Box>
      )}
      {slots.data && slots.data.length === 0 && <Alert severity="info" sx={{ mt: 1 }}>This court has no slots that day.</Alert>}

      {message && <Alert severity="warning" sx={{ mt: 2 }} data-testid="hold-message">{message}</Alert>}
      <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
        {summary ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
            <Box>
              <Typography variant="subtitle1" fontWeight={800} data-testid="selection-summary">{when(summary.start, timeZone)} – {clock(summary.end, timeZone)} · {formatPrice(summary.price, currency)}</Typography>
              <Typography variant="caption" color="text.secondary">{court?.name} · {sport?.name} · {summary.count} slot{summary.count === 1 ? '' : 's'}. We hold it for you while you pay.</Typography>
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

export default VenueDetailPage;
