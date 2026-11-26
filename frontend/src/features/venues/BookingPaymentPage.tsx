import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { venuesApi } from '@/api/venues';
import { paymentsApi } from '@/api/payments';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { mmss, secondsLeft, when } from '@/lib/venueSlots';
import type { PaymentResponse } from '@/types/payments';

const RETRYABLE = ['GATEWAY_TIMEOUT', 'GATEWAY_ERROR', 'OPTIMISTIC_LOCKING_CONFLICT'];

/**
 * Pays for a held booking. The bookings reuse the payments API (the booking id goes in as the payment's `orderId`). The amount sent is only an
 * echo of the booking's own price: the server charges the booking's price snapshot whatever the request says. Payments here use the demonstration
 * gateway: no real money moves.
 */
const BookingPaymentPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const booking = useQuery({ queryKey: ['booking', id], queryFn: () => venuesApi.getBooking(id), refetchOnWindowFocus: false });
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<PaymentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);

  const b = booking.data;
  const timeZone = 'Asia/Kolkata';
  const left = b ? secondsLeft(b.heldUntil, now) : 0;
  const payable = !!b && b.status === 'HELD' && left > 0;

  const capture = async (payment: PaymentResponse) => {
    try {
      await paymentsApi.capturePayment(payment.id);
      navigate('/bookings?paid=' + id, { replace: true });
    } catch (err) {
      const apiError = getApiError(err);
      if (apiError && RETRYABLE.includes(apiError.code)) {
        setPending(payment);
        setError(`${apiError.message} Retrying is safe: you will not be charged twice.`);
      } else if (apiError?.code === 'PAID_ITEM_NO_LONGER_AVAILABLE') {
        setPending(null);
        setError(apiError.message);
      } else {
        setPending(null);
        setError(getApiErrorMessage(err, 'The payment could not be completed.'));
      }
    }
  };

  const pay = async () => {
    if (!b || busy) return;
    setBusy(true);
    setError(null);
    try {
      const payment = pending ?? await paymentsApi.createPayment({ orderId: b.id, amount: b.price, currency: b.currency, paymentMethod: 'UPI', paymentProvider: 'MOCK' });
      if (payment.status === 'AUTHORIZED' || payment.status === 'PROCESSING') await capture(payment);
      else setError(payment.failureReason || 'The payment was declined.');
    } catch (err) {
      setError(getApiErrorMessage(err, 'The payment could not be started.'));
    } finally {
      setBusy(false);
      void booking.refetch();
    }
  };

  if (booking.isError) return <ErrorState body="We couldn't load that booking." onRetry={() => void booking.refetch()} />;
  if (!b) return <Skeleton variant="rounded" height={160} />;
  return (
    <Box sx={{ maxWidth: 560, mx: 'auto' }}>
      <Typography variant="h3" component="h1">Pay for your booking</Typography>
      <Paper variant="outlined" sx={{ p: 2.5, mt: 2 }}>
        <Typography variant="subtitle1" fontWeight={800}>{b.venueName ?? 'Venue'}{b.venueCity ? `, ${b.venueCity}` : ''}</Typography>
        {(b.courtName || b.sportName) && <Typography variant="body2" data-testid="booking-court-sport">{[b.courtName, b.sportName].filter(Boolean).join(' · ')}</Typography>}
        <Typography variant="body2" color="text.secondary">{b.bookingNumber} · {when(b.startAt, timeZone)} – {when(b.endAt, timeZone).split(', ').pop()}</Typography>
        <Typography variant="h5" sx={{ my: 1.5 }} data-testid="booking-amount">{formatPrice(b.price, b.currency)}</Typography>

        {payable && <Alert severity={left < 60 ? 'warning' : 'info'} data-testid="hold-countdown">Your slot is held for {mmss(left)}. Pay before it runs out.</Alert>}
        {b.status === 'HELD' && left === 0 && <Alert severity="warning" data-testid="hold-lapsed">This hold has ended. The slot may already have been released: go back and choose a slot again.</Alert>}
        {b.status === 'CONFIRMED' && <Alert severity="success">This booking is paid and confirmed.</Alert>}
        {(b.status === 'EXPIRED' || b.status === 'CANCELLED') && <Alert severity="error">This booking is {b.status.toLowerCase()} and can no longer be paid.</Alert>}
        {error && <Alert severity="error" sx={{ mt: 1.5 }} data-testid="pay-error">{error}</Alert>}
        <Alert severity="info" variant="outlined" sx={{ mt: 1.5 }}>Demonstration payment: this uses the test gateway and no real money moves.</Alert>

        <Stack direction="row" spacing={1.5} sx={{ mt: 2 }}>
          <Button variant="contained" disabled={!payable || busy} onClick={() => void pay()} data-testid="pay-booking">{pending ? 'Retry payment' : `Pay ${formatPrice(b.price, b.currency)}`}</Button>
          <Button component={RouterLink} to="/bookings">My bookings</Button>
        </Stack>
      </Paper>
    </Box>
  );
};

export default BookingPaymentPage;
