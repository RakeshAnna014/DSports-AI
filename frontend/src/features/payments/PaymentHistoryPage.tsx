import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Container,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  CircularProgress,
  Alert,
  Button,
} from '@mui/material';
import { paymentsApi } from '@/api/payments';
import type { PaymentSummaryResponse } from '@/types/payments';

const statusColor: Record<string, 'success' | 'error' | 'warning' | 'info' | 'default'> = {
  SUCCESS: 'success',
  FAILED: 'error',
  CANCELLED: 'warning',
  REFUNDED: 'info',
  CREATED: 'default',
  PENDING: 'warning',
  AUTHORIZED: 'info',
};

const PaymentHistoryPage = () => {
  const navigate = useNavigate();
  const [payments, setPayments] = useState<PaymentSummaryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadPayments();
  }, []);

  const loadPayments = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await paymentsApi.getPaymentHistory();
      setPayments(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load payment history';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <Container maxWidth="md" sx={{ py: 4, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Typography variant="h4" gutterBottom>
        Payment History
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {payments.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography variant="body1" color="text.secondary" gutterBottom>
            No payments found.
          </Typography>
          <Button variant="contained" onClick={() => navigate('/orders')}>
            View Orders
          </Button>
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Reference</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Method</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Date</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {payments.map((p) => (
                <TableRow key={p.id} hover>
                  <TableCell>{p.paymentReference}</TableCell>
                  <TableCell>{p.currency} {p.amount.toFixed(2)}</TableCell>
                  <TableCell>{p.paymentMethod || '-'}</TableCell>
                  <TableCell>
                    <Chip
                      label={p.status}
                      color={statusColor[p.status] || 'default'}
                      size="small"
                    />
                  </TableCell>
                  <TableCell>{p.paidAt ? new Date(p.paidAt).toLocaleDateString() : new Date(p.createdAt).toLocaleDateString()}</TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      onClick={() => navigate(`/orders/${p.orderId}`)}
                    >
                      View Order
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Container>
  );
};

export default PaymentHistoryPage;
