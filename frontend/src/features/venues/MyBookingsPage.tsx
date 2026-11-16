import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { venuesApi } from '@/api/venues';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { refundSentence, when } from '@/lib/venueSlots';
import type { Booking, BookingStatus, CancelResult } from '@/types/venues';

const STATUS: Record<BookingStatus, { label: string; color: 'warning' | 'success' | 'default' | 'error' | 'info' }> = {
  HELD: { label: 'Awaiting payment', color: 'warning' },
  CONFIRMED: { label: 'Confirmed', color: 'success' },
  EXPIRED: { label: 'Expired', color: 'default' },
  CANCELLED: { label: 'Cancelled', color: 'error' },
  COMPLETED: { label: 'Completed', color: 'info' },
  NO_SHOW: { label: 'No show', color: 'default' },
};

/** Your bookings: pay for a held slot, or cancel one. The cancellation share is decided by the server and shown after the fact. */
const MyBookingsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [toCancel, setToCancel] = useState<Booking | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [outcome, setOutcome] = useState<CancelResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['my-bookings'], queryFn: venuesApi.myBookings, enabled: isAuthenticated });

  const cancel = useMutation({
    mutationFn: (id: string) => venuesApi.cancel(id),
    onSuccess: (result) => { setOutcome(result); setConfirming(false); setError(null); void queryClient.invalidateQueries({ queryKey: ['my-bookings'] }); },
    onError: (err) => { setConfirming(false); setError(getApiErrorMessage(err, 'That booking could not be cancelled.')); },
  });

  if (!isAuthenticated) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Sign in to see your bookings" body="Your bookings are private to your account."
      action={<Button variant="contained" onClick={() => navigate('/login')}>Sign in</Button>} />;
  }
  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
        <Typography variant="h3" component="h1">My bookings</Typography>
        <Button variant="contained" component={RouterLink} to="/venues">Book a court</Button>
      </Stack>
      {searchParams.get('paid') && <Alert severity="success" sx={{ mb: 2 }} data-testid="paid-banner">Payment received: your booking is confirmed.</Alert>}
      {outcome && <Alert severity="info" sx={{ mb: 2 }} onClose={() => setOutcome(null)} data-testid="cancel-outcome">Booking {outcome.booking.bookingNumber} cancelled. {refundSentence(outcome)}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {isError && <ErrorState body="We couldn't load your bookings." onRetry={() => void refetch()} />}
      {isLoading && <Stack spacing={1.5}>{[0, 1].map((i) => <Skeleton key={i} variant="rounded" height={88} />)}</Stack>}
      {data && data.length === 0 && <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No bookings yet" body="Pick a sport, a venue and a slot to make your first booking." />}
      <Stack spacing={1.5}>
        {data?.map((b) => {
          const s = STATUS[b.status];
          const cancellable = b.status === 'HELD' || b.status === 'CONFIRMED';
          return (
            <Paper key={b.id} variant="outlined" sx={{ p: 2 }} data-testid="booking-card">
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                <Box>
                  <Typography variant="subtitle1" fontWeight={800}>{b.venueName ?? 'Venue'}{b.venueCity ? `, ${b.venueCity}` : ''}</Typography>
                  {(b.courtName || b.sportName) && <Typography variant="body2" data-testid="booking-court-sport">{[b.courtName, b.sportName].filter(Boolean).join(' · ')}</Typography>}
                  <Typography variant="body2" color="text.secondary">{b.bookingNumber} · {when(b.startAt, 'Asia/Kolkata')} · {formatPrice(b.price, b.currency)}</Typography>
                  <Chip size="small" color={s.color} label={s.label} sx={{ mt: 1 }} />
                </Box>
                <Stack direction="row" spacing={1} alignItems="center">
                  {b.status === 'HELD' && <Button variant="contained" size="small" component={RouterLink} to={`/bookings/${b.id}/pay`}>Pay now</Button>}
                  {cancellable && <Button size="small" color="inherit" onClick={() => { setToCancel(b); setConfirming(true); }} data-testid="cancel-booking">Cancel</Button>}
                </Stack>
              </Stack>
            </Paper>
          );
        })}
      </Stack>

      <Dialog open={confirming} onClose={() => setConfirming(false)} TransitionProps={{ onExited: () => setToCancel(null) }}>
        <DialogTitle>Cancel {toCancel?.bookingNumber}?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {toCancel?.status === 'HELD'
              ? 'This releases the slot. Nothing has been charged.'
              : 'The refund depends on how close the start is: the earlier you cancel, the more you get back, and very late cancellations are not refunded. We will show you the exact share right after. A refund is paid out once it has been approved.'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Keep booking</Button>
          <Button color="error" variant="contained" disabled={cancel.isPending} onClick={() => toCancel && cancel.mutate(toCancel.id)} data-testid="confirm-cancel">Cancel booking</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default MyBookingsPage;
