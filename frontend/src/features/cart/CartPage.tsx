import { Box, Typography, Button } from '@mui/material';
import { useNavigate } from 'react-router-dom';

const CartPage = () => {
  const navigate = useNavigate();

  return (
    <Box sx={{ textAlign: 'center', py: 8 }}>
      <Typography variant="h4" gutterBottom>
        Your Cart
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 4 }}>
        Your shopping cart is empty.
      </Typography>
      <Button variant="contained" size="large" onClick={() => navigate('/products')}>
        Browse Products
      </Button>
    </Box>
  );
};

export default CartPage;
