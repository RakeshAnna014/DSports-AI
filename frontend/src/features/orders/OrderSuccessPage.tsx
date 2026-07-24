import { useNavigate, useSearchParams } from 'react-router-dom';
import { Box, Typography, Button, Card, CardContent, Divider, Stack } from '@mui/material';
import { CheckCircleOutline, ReceiptLongOutlined, ShoppingBagOutlined } from '@mui/icons-material';

const OrderSuccessPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const orderId = searchParams.get('id');
  const orderNumber = searchParams.get('orderNumber');

  return (
    <Box sx={{ maxWidth: 600, mx: 'auto', py: 6 }}>
      <Card variant="outlined" sx={{ borderRadius: 2, textAlign: 'center' }}>
        <CardContent sx={{ py: 6 }}>
          <CheckCircleOutline sx={{ fontSize: 80, color: 'success.main', mb: 2 }} />
          <Typography variant="h4" fontWeight={700} gutterBottom>
            Order Placed Successfully!
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
            Thank you for your purchase. Your order has been placed and is being processed.
          </Typography>

          {orderNumber && (
            <Box
              sx={{
                bgcolor: 'grey.50',
                borderRadius: 1,
                p: 2,
                mb: 3,
                display: 'inline-block',
              }}
            >
              <Typography variant="body2" color="text.secondary">
                Order Number
              </Typography>
              <Typography variant="h6" fontWeight={700}>
                {orderNumber}
              </Typography>
            </Box>
          )}

          <Divider sx={{ my: 3 }} />

          <Stack spacing={2} direction={{ xs: 'column', sm: 'row' }} justifyContent="center">
            {orderId && (
              <Button
                variant="contained"
                startIcon={<ReceiptLongOutlined />}
                onClick={() => navigate(`/orders/${orderId}`)}
              >
                View Order Details
              </Button>
            )}
            <Button
              variant="outlined"
              startIcon={<ReceiptLongOutlined />}
              onClick={() => navigate('/orders')}
            >
              My Orders
            </Button>
            <Button
              variant="text"
              startIcon={<ShoppingBagOutlined />}
              onClick={() => navigate('/products')}
            >
              Continue Shopping
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
};

export default OrderSuccessPage;
