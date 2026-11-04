import { useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  Container,
  Paper,
  Typography,
  Box,
  Button,
  Alert,
  CircularProgress,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { paymentsApi } from '@/api/payments';
import { ordersApi } from '@/api/orders';
import { getApiErrorMessage } from '@/api/errors';
import type { PaymentResponse } from '@/types/payments';
import { CAPTURED_STATUSES } from '@/types/payments';
import type { OrderResponse } from '@/types/orders';

/** Shows the confirmed outcome as stored by the backend, never values passed in the URL. */
const PaymentSuccessPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const paymentId = searchParams.get('paymentId') || '';

  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!paymentId) {
      setError('No payment specified.');
      return;
    }
    paymentsApi
      .getPayment(paymentId)
      .then(async (p) => {
        setPayment(p);
        setOrder(await ordersApi.getOrder(p.orderId));
      })
      .catch((err) => setError(getApiErrorMessage(err, 'Could not load the payment')));
  }, [paymentId]);

  if (error) {
    return (
      <Container maxWidth="sm" sx={{ py: 8 }}>
        <Alert severity="error">{error}</Alert>
      </Container>
    );
  }

  if (!payment) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (!CAPTURED_STATUSES.includes(payment.status)) {
    return (
      <Container maxWidth="sm" sx={{ py: 8 }}>
        <Alert severity="warning" sx={{ mb: 2 }}>
          This payment has not been completed (status: {payment.status}).
        </Alert>
        <Button variant="contained" onClick={() => navigate(`/payment?orderId=${payment.orderId}`)}>
          Go to payment
        </Button>
      </Container>
    );
  }

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Paper sx={{ p: 4, textAlign: 'center' }}>
        <CheckCircleIcon sx={{ fontSize: 80, color: 'success.main', mb: 2 }} />
        <Typography variant="h4" gutterBottom>
          Payment Successful!
        </Typography>
        <Alert severity="success" sx={{ mb: 3 }}>
          Your payment has been captured and your order is confirmed.
        </Alert>

        <TableContainer>
          <Table size="small">
            <TableBody>
              <TableRow>
                <TableCell>Payment Reference</TableCell>
                <TableCell align="right">{payment.paymentReference}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>Amount Paid</TableCell>
                <TableCell align="right">{payment.currency} {payment.amount.toFixed(2)}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>Order</TableCell>
                <TableCell align="right">{order?.orderNumber ?? payment.orderId}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>Order Status</TableCell>
                <TableCell align="right" data-testid="order-status">{order?.status ?? '…'}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </TableContainer>

        <Box sx={{ mt: 3, display: 'flex', gap: 2, justifyContent: 'center' }}>
          <Button variant="contained" onClick={() => navigate(`/orders/${payment.orderId}`)}>
            View Order
          </Button>
          <Button variant="outlined" onClick={() => navigate('/orders')}>
            My Orders
          </Button>
        </Box>
      </Paper>
    </Container>
  );
};

export default PaymentSuccessPage;
