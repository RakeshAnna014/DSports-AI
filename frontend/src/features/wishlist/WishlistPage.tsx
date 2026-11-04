import {
  Box,
  Typography,
  Button,
  IconButton,
  Card,
  CardContent,
  CardMedia,
  Stack,
  Chip,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  FavoriteBorder,
  DeleteOutline,
  ShoppingCartOutlined,
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useWishlistStore } from '@/store/wishlistStore';
import { formatPrice, computeStockLevel } from '@/lib/productUtils';
import { useEffect } from 'react';
import { useAuthStore } from '@/store/authStore';

const WishlistPage = () => {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuthStore();
  const { items, totalItems, removeItem, moveToCart, clearWishlist, refreshWishlist } = useWishlistStore();

  useEffect(() => {
    if (isAuthenticated) {
      refreshWishlist();
    }
  }, [isAuthenticated, refreshWishlist]);

  if (!isAuthenticated) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <FavoriteBorder sx={{ fontSize: 64, color: 'text.disabled', mb: 2 }} />
        <Typography variant="h5" gutterBottom>
          Sign in to view your wishlist
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 4 }}>
          Save your favorite products and access them anytime.
        </Typography>
        <Button variant="contained" size="large" onClick={() => navigate('/login')}>
          Sign In
        </Button>
      </Box>
    );
  }

  if (items.length === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <FavoriteBorder sx={{ fontSize: 64, color: 'text.disabled', mb: 2 }} />
        <Typography variant="h5" gutterBottom>
          Your wishlist is empty
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 4 }}>
          Save items you love to your wishlist and find them here.
        </Typography>
        <Button variant="contained" size="large" onClick={() => navigate('/products')}>
          Browse Products
        </Button>
      </Box>
    );
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h4" fontWeight={700}>
          Wishlist ({totalItems} {totalItems === 1 ? 'item' : 'items'})
        </Typography>
        <Button
          variant="outlined"
          color="error"
          size="small"
          startIcon={<DeleteOutline />}
          onClick={clearWishlist}
        >
          Clear All
        </Button>
      </Box>

      <Grid container spacing={2}>
        {items.map((item) => {
          const stock = computeStockLevel();
          return (
            <Grid key={item.itemId} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card sx={{ borderRadius: 2, height: '100%' }} variant="outlined">
                <CardMedia
                  component="img"
                  image={item.imageUrl}
                  alt={item.name}
                  sx={{
                    height: 200,
                    objectFit: 'cover',
                    bgcolor: 'grey.100',
                    cursor: 'pointer',
                  }}
                  onClick={() => navigate(`/products/${item.productId}`)}
                />
                <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                  <Typography
                    variant="subtitle2"
                    fontWeight={600}
                    noWrap
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/products/${item.productId}`)}
                  >
                    {item.name}
                  </Typography>
                  <Typography variant="body2" color="primary.main" fontWeight={600} sx={{ mt: 0.5 }}>
                    {formatPrice(item.price)}
                  </Typography>
                  <Chip
                    label={stock.label}
                    size="small"
                    color={stock.level === 'in_stock' ? 'success' : stock.level === 'low_stock' ? 'warning' : 'error'}
                    variant="outlined"
                    sx={{ mt: 1 }}
                  />

                  <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                    <Button
                      variant="contained"
                      size="small"
                      fullWidth
                      startIcon={<ShoppingCartOutlined />}
                      onClick={() => moveToCart(item.itemId)}
                    >
                      Move to Cart
                    </Button>
                    <IconButton
                      size="small"
                      color="error"
                      onClick={() => removeItem(item.itemId)}
                      aria-label="Remove from wishlist"
                    >
                      <DeleteOutline fontSize="small" />
                    </IconButton>
                  </Stack>
                </CardContent>
              </Card>
            </Grid>
          );
        })}
      </Grid>
    </Box>
  );
};

export default WishlistPage;
