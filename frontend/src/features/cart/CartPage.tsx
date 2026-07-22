import {
  Box,
  Typography,
  Button,
  IconButton,
  Card,
  CardContent,
  CardMedia,
  Divider,
  Stack,
  Alert,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  Add,
  Remove,
  DeleteOutline,
  ShoppingBagOutlined,
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useCartStore } from '@/store/cartStore';
import { formatPrice } from '@/lib/productUtils';

const CartPage = () => {
  const navigate = useNavigate();
  const { items, totalItems, updateQuantity, removeItem } = useCartStore();

  const subtotal = items.reduce((sum, item) => sum + item.price * item.quantity, 0);

  if (items.length === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <ShoppingBagOutlined sx={{ fontSize: 64, color: 'text.disabled', mb: 2 }} />
        <Typography variant="h5" gutterBottom>
          Your cart is empty
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 4 }}>
          Looks like you have not added any products yet.
        </Typography>
        <Button variant="contained" size="large" onClick={() => navigate('/products')}>
          Browse Products
        </Button>
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h4" fontWeight={700} gutterBottom>
        Cart ({totalItems} {totalItems === 1 ? 'item' : 'items'})
      </Typography>

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Stack spacing={2}>
            {items.map((item) => (
              <Card key={item.productId} sx={{ borderRadius: 2 }} variant="outlined">
                <CardContent sx={{ display: 'flex', gap: 2, p: 2, '&:last-child': { pb: 2 } }}>
                  <CardMedia
                    component="img"
                    image={item.imageUrl}
                    alt={item.name}
                    sx={{
                      width: 100,
                      height: 100,
                      borderRadius: 1,
                      objectFit: 'cover',
                      flexShrink: 0,
                      bgcolor: 'grey.100',
                    }}
                  />
                  <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                    <Typography variant="subtitle2" fontWeight={600} noWrap>
                      {item.name}
                    </Typography>
                    <Typography variant="body2" color="primary.main" fontWeight={600} sx={{ mt: 0.5 }}>
                      {formatPrice(item.price)}
                    </Typography>

                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }}>
                      <IconButton
                        size="small"
                        onClick={() => updateQuantity(item.productId, item.quantity - 1)}
                        aria-label="Decrease quantity"
                      >
                        <Remove fontSize="small" />
                      </IconButton>
                      <Typography variant="body2" fontWeight={600} sx={{ minWidth: 24, textAlign: 'center' }}>
                        {item.quantity}
                      </Typography>
                      <IconButton
                        size="small"
                        onClick={() => updateQuantity(item.productId, item.quantity + 1)}
                        aria-label="Increase quantity"
                      >
                        <Add fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => removeItem(item.productId)}
                        aria-label="Remove item"
                        sx={{ ml: 'auto' }}
                      >
                        <DeleteOutline fontSize="small" />
                      </IconButton>
                    </Box>
                  </Box>
                </CardContent>
              </Card>
            ))}
          </Stack>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ borderRadius: 2, position: 'sticky', top: 88 }} variant="outlined">
            <CardContent>
              <Typography variant="h6" fontWeight={600} gutterBottom>
                Order Summary
              </Typography>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Subtotal</Typography>
                <Typography variant="body2" fontWeight={600}>{formatPrice(subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Shipping</Typography>
                <Typography variant="body2" color="text.secondary">Calculated at checkout</Typography>
              </Box>
              <Divider sx={{ my: 1.5 }} />
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="subtitle1" fontWeight={700}>Total</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="primary.main">
                  {formatPrice(subtotal)}
                </Typography>
              </Box>
              <Button
                variant="contained"
                size="large"
                fullWidth
                onClick={() => alert('Checkout not implemented yet')}
              >
                Proceed to Checkout
              </Button>
              <Button
                variant="text"
                size="small"
                fullWidth
                onClick={() => navigate('/products')}
                sx={{ mt: 1 }}
              >
                Continue Shopping
              </Button>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default CartPage;
