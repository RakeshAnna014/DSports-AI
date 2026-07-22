import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import {
  Box,
  Typography,
  CircularProgress,
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
} from '@mui/material';
import { catalogApi } from '@/api/catalog';
import type { ProductDetail } from '@/types/catalog';

const ProductDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id) {
      setError('Product ID is missing');
      setLoading(false);
      return;
    }
    catalogApi.getProduct(id)
      .then(setProduct)
      .catch((err) => {
        setError(err instanceof AxiosError && err.response?.data?.message ? err.response.data.message : 'Failed to load product');
      })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return (
      <Box>
        <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>
        <Button variant="outlined" onClick={() => navigate('/products')}>Back to Products</Button>
      </Box>
    );
  }

  if (!product) {
    return (
      <Box>
        <Alert severity="warning">Product not found.</Alert>
        <Button variant="outlined" onClick={() => navigate('/products')} sx={{ mt: 2 }}>Back to Products</Button>
      </Box>
    );
  }

  return (
    <Box>
      <Button variant="text" onClick={() => navigate('/products')} sx={{ mb: 2 }}>
        &larr; Back to Products
      </Button>
      <Card>
        <CardContent>
          <Typography variant="h4" gutterBottom>{product.name}</Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            SKU: {product.sku}
          </Typography>
          {product.description && (
            <Typography variant="body1" sx={{ mt: 2 }}>{product.description}</Typography>
          )}
          <Box sx={{ display: 'flex', gap: 1, mt: 2 }}>
            <Chip label={product.status} color="primary" variant="outlined" size="small" />
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default ProductDetailPage;
