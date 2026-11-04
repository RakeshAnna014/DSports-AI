import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Container,
  Paper,
  Typography,
  Box,
  Stepper,
  Step,
  StepLabel,
  Button,
  CircularProgress,
  Alert,
  Radio,
  RadioGroup,
  FormControlLabel,
  FormControl,
  FormLabel,
  Divider,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
} from '@mui/material';
import { paymentsApi } from '@/api/payments';
import { ordersApi } from '@/api/orders';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import type { OrderResponse } from '@/types/orders';
import type { PaymentMethod, PaymentProvider, PaymentResponse } from '@/types/payments';
import { CAPTURED_STATUSES } from '@/types/payments';

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Credit / Debit Card' },
  { value: 'NET_BANKING', label: 'Net Banking' },
  { value: 'WALLET', label: 'Wallet' },
  { value: 'COD', label: 'Cash on Delivery' },
];

const PAYMENT_PROVIDER: PaymentProvider = 'MOCK';

const steps = ['Order Summary', 'Payment Method', 'Make Payment'];

/** authorizing → capturing → done; a capture that times out can be retried on the same payment. */
type Phase = 'idle' | 'authorizing' | 'capturing' | 'captureRetry';

const RETRYABLE_CAPTURE_CODES = ['GATEWAY_TIMEOUT', 'GATEWAY_ERROR', 'OPTIMISTIC_LOCKING_CONFLICT'];

const PaymentPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const orderId = searchParams.get('orderId') || '';

  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loadingOrder, setLoadingOrder] = useState(true);
  const [activeStep, setActiveStep] = useState(0);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('UPI');
  const [phase, setPhase] = useState<Phase>('idle');
  const [pendingPayment, setPendingPayment] = useState<PaymentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const busy = phase === 'authorizing' || phase === 'capturing';

  useEffect(() => {
    if (!orderId) {
      setLoadingOrder(false);
      return;
    }
    ordersApi
      .getOrder(orderId)
      .then(setOrder)
      .catch((err) => setError(getApiErrorMessage(err, 'Could not load the order')))
      .finally(() => setLoadingOrder(false));
  }, [orderId]);

  const goToResult = useCallback(
    (payment: PaymentResponse) => {
      if (CAPTURED_STATUSES.includes(payment.status)) {
        navigate(`/payment-success?paymentId=${payment.id}`, { replace: true });
      } else {
        navigate(
          `/payment-failure?orderId=${orderId}&reason=${encodeURIComponent(payment.failureReason || 'Payment declined')}`,
          { replace: true },
        );
      }
    },
    [navigate, orderId],
  );

  const capture = useCallback(
    async (payment: PaymentResponse) => {
      setPhase('capturing');
      setError(null);
      try {
        const captured = await paymentsApi.capturePayment(payment.id);
        setPendingPayment(null);
        goToResult(captured);
      } catch (err) {
        const apiError = getApiError(err);
        if (apiError && RETRYABLE_CAPTURE_CODES.includes(apiError.code)) {
          setPendingPayment(payment);
          setPhase('captureRetry');
          setError(`${apiError.message} Your card has not been charged twice; retrying is safe.`);
          return;
        }
        setPhase('idle');
        setError(getApiErrorMessage(err, 'Payment could not be completed'));
      }
    },
    [goToResult],
  );

  const handleMakePayment = async () => {
    if (!order || busy) return;
    setPhase('authorizing');
    setError(null);
    try {
      const payment = await paymentsApi.createPayment({
        orderId: order.id,
        amount: order.grandTotal,
        currency: order.currency,
        paymentMethod,
        paymentProvider: PAYMENT_PROVIDER,
      });
      if (payment.status === 'AUTHORIZED' || payment.status === 'PROCESSING') {
        await capture(payment);
      } else {
        setPhase('idle');
        goToResult(payment);
      }
    } catch (err) {
      setPhase('idle');
      const apiError = getApiError(err);
      if (apiError?.code === 'ORDER_ALREADY_PAID') {
        navigate(`/orders/${orderId}`, { replace: true });
        return;
      }
      setError(getApiErrorMessage(err, 'Payment could not be started'));
    }
  };

  if (!orderId) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity="error">No order specified for payment.</Alert>
      </Container>
    );
  }

  if (loadingOrder) {
    return (
      <Container maxWidth="md" sx={{ py: 4, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (!order) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity="error">{error || 'Order not found.'}</Alert>
      </Container>
    );
  }

  if (order.status !== 'PENDING_PAYMENT') {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity={order.status === 'CANCELLED' ? 'warning' : 'info'} sx={{ mb: 2 }}>
          Order {order.orderNumber} is {order.status.replace('_', ' ').toLowerCase()} and cannot be paid.
        </Alert>
        <Button variant="contained" onClick={() => navigate(`/orders/${order.id}`)}>
          View order
        </Button>
      </Container>
    );
  }

  const total = `${order.currency} ${order.grandTotal.toFixed(2)}`;

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Typography variant="h4" gutterBottom>
        Complete Payment
      </Typography>

      <Stepper activeStep={activeStep} sx={{ mb: 4 }}>
        {steps.map((label) => (
          <Step key={label}>
            <StepLabel>{label}</StepLabel>
          </Step>
        ))}
      </Stepper>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={busy ? undefined : () => setError(null)}>
          {error}
        </Alert>
      )}

      {activeStep === 0 && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>Order Summary</Typography>
          <TableContainer>
            <Table size="small">
              <TableBody>
                <TableRow>
                  <TableCell>Order Number</TableCell>
                  <TableCell align="right">{order.orderNumber}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>Items</TableCell>
                  <TableCell align="right">{order.items.reduce((n, i) => n + i.quantity, 0)}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>Amount</TableCell>
                  <TableCell align="right">{total}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
            <Button variant="contained" onClick={() => setActiveStep(1)}>
              Continue
            </Button>
          </Box>
        </Paper>
      )}

      {activeStep === 1 && (
        <Paper sx={{ p: 3 }}>
          <FormControl>
            <FormLabel>Select Payment Method</FormLabel>
            <RadioGroup value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}>
              {PAYMENT_METHODS.map((m) => (
                <FormControlLabel key={m.value} value={m.value} control={<Radio />} label={m.label} />
              ))}
            </RadioGroup>
          </FormControl>

          <Divider sx={{ my: 2 }} />

          <Typography variant="body2" color="text.secondary" gutterBottom>
            Payment Provider: Mock Payment (Test)
          </Typography>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
            <Button onClick={() => setActiveStep(0)}>Back</Button>
            <Button variant="contained" onClick={() => setActiveStep(2)}>
              Continue
            </Button>
          </Box>
        </Paper>
      )}

      {activeStep === 2 && (
        <Paper sx={{ p: 3, textAlign: 'center' }}>
          <Typography variant="h6" gutterBottom>
            Confirm Payment
          </Typography>
          <Typography variant="body1" gutterBottom>
            Amount: {total}
          </Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Method: {paymentMethod}
          </Typography>

          {busy && (
            <Box sx={{ my: 2, display: 'flex', gap: 1, justifyContent: 'center', alignItems: 'center' }}>
              <CircularProgress size={20} />
              <Typography variant="body2" data-testid="payment-phase">
                {phase === 'authorizing' ? 'Authorizing payment…' : 'Confirming payment…'}
              </Typography>
            </Box>
          )}

          <Box sx={{ mt: 3, display: 'flex', gap: 2, justifyContent: 'center' }}>
            <Button variant="outlined" onClick={() => setActiveStep(1)} disabled={busy || phase === 'captureRetry'}>
              Back
            </Button>
            {phase === 'captureRetry' && pendingPayment ? (
              <Button variant="contained" color="warning" onClick={() => capture(pendingPayment)}>
                Retry confirmation
              </Button>
            ) : (
              <Button variant="contained" color="primary" onClick={handleMakePayment} disabled={busy}>
                {busy ? <CircularProgress size={24} /> : `Pay ${total}`}
              </Button>
            )}
          </Box>
        </Paper>
      )}
    </Container>
  );
};

export default PaymentPage;
