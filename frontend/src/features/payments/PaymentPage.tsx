import { useState } from 'react';
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
import type { PaymentMethod, PaymentProvider } from '@/types/payments';

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Credit / Debit Card' },
  { value: 'NET_BANKING', label: 'Net Banking' },
  { value: 'WALLET', label: 'Wallet' },
  { value: 'COD', label: 'Cash on Delivery' },
];

const PAYMENT_PROVIDERS: { value: PaymentProvider; label: string }[] = [
  { value: 'MOCK', label: 'Mock Payment (Test)' },
];

const steps = ['Order Summary', 'Payment Method', 'Make Payment'];

const PaymentPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const orderId = searchParams.get('orderId') || '';
  const amount = parseFloat(searchParams.get('amount') || '0');
  const currency = searchParams.get('currency') || 'INR';
  const orderNumber = searchParams.get('orderNumber') || '';

  const [activeStep, setActiveStep] = useState(0);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('UPI');
  const [paymentProvider] = useState<PaymentProvider>('MOCK');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleNext = () => setActiveStep((prev) => prev + 1);
  const handleBack = () => setActiveStep((prev) => prev - 1);

  const handleMakePayment = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await paymentsApi.createPayment({
        orderId,
        amount,
        currency,
        paymentMethod,
        paymentProvider,
      });
      if (result.status === 'SUCCESS') {
        navigate(`/payment-success?paymentId=${result.id}&orderId=${orderId}&amount=${amount}&currency=${currency}&paymentReference=${result.paymentReference}`);
      } else if (result.status === 'FAILED') {
        navigate(`/payment-failure?paymentId=${result.id}&orderId=${orderId}&reason=${encodeURIComponent(result.failureReason || 'Payment declined')}`);
      } else {
        setActiveStep(2);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Payment failed';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  if (!orderId) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity="error">No order specified for payment.</Alert>
      </Container>
    );
  }

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
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
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
                  <TableCell align="right">{orderNumber || orderId}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>Amount</TableCell>
                  <TableCell align="right">{currency} {amount.toFixed(2)}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
            <Button variant="contained" onClick={handleNext}>
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
            Payment Provider: {PAYMENT_PROVIDERS.find((p) => p.value === paymentProvider)?.label}
          </Typography>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
            <Button onClick={handleBack}>Back</Button>
            <Button variant="contained" onClick={handleNext}>
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
            Amount: {currency} {amount.toFixed(2)}
          </Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Method: {paymentMethod} | Provider: {paymentProvider}
          </Typography>

          <Box sx={{ mt: 3, display: 'flex', gap: 2, justifyContent: 'center' }}>
            <Button variant="outlined" onClick={handleBack} disabled={loading}>
              Back
            </Button>
            <Button variant="contained" color="primary" onClick={handleMakePayment} disabled={loading}>
              {loading ? <CircularProgress size={24} /> : `Pay ${currency} ${amount.toFixed(2)}`}
            </Button>
          </Box>
        </Paper>
      )}
    </Container>
  );
};

export default PaymentPage;
