import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  Container,
  Paper,
  Typography,
  Box,
  Button,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';

const PaymentSuccessPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const paymentReference = searchParams.get('paymentReference') || '';
  const orderId = searchParams.get('orderId') || '';
  const amount = searchParams.get('amount') || '0';
  const currency = searchParams.get('currency') || 'INR';

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Paper sx={{ p: 4, textAlign: 'center' }}>
        <CheckCircleIcon sx={{ fontSize: 80, color: 'success.main', mb: 2 }} />
        <Typography variant="h4" gutterBottom>
          Payment Successful!
        </Typography>
        <Alert severity="success" sx={{ mb: 3 }}>
          Your payment has been processed successfully.
        </Alert>

        <TableContainer>
          <Table size="small">
            <TableBody>
              <TableRow>
                <TableCell>Payment Reference</TableCell>
                <TableCell align="right">{paymentReference}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>Amount Paid</TableCell>
                <TableCell align="right">{currency} {parseFloat(amount).toFixed(2)}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>Order ID</TableCell>
                <TableCell align="right">{orderId}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </TableContainer>

        <Box sx={{ mt: 3, display: 'flex', gap: 2, justifyContent: 'center' }}>
          <Button variant="contained" onClick={() => navigate(`/orders/${orderId}`)}>
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
