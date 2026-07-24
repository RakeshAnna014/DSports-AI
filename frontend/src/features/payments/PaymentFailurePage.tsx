import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  Container,
  Paper,
  Typography,
  Box,
  Button,
  Alert,
} from '@mui/material';
import ErrorIcon from '@mui/icons-material/Error';

const PaymentFailurePage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const orderId = searchParams.get('orderId') || '';
  const reason = searchParams.get('reason') || 'Payment was declined';

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Paper sx={{ p: 4, textAlign: 'center' }}>
        <ErrorIcon sx={{ fontSize: 80, color: 'error.main', mb: 2 }} />
        <Typography variant="h4" gutterBottom>
          Payment Failed
        </Typography>
        <Alert severity="error" sx={{ mb: 3 }}>
          {reason}
        </Alert>

        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Your payment could not be processed. Please try again or use a different payment method.
        </Typography>

        <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center' }}>
          <Button variant="contained" onClick={() => navigate(`/payment?orderId=${orderId}`)}>
            Try Again
          </Button>
          <Button variant="outlined" onClick={() => navigate('/orders')}>
            My Orders
          </Button>
        </Box>
      </Paper>
    </Container>
  );
};

export default PaymentFailurePage;
