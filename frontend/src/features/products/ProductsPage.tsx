import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import {
  Box,
  Typography,
  Card,
  CardContent,
  CardActionArea,
  TextField,
  MenuItem,
  CircularProgress,
  Alert,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { catalogApi } from '@/api/catalog';
import type { ProductSummary, Sport } from '@/types/catalog';

const ProductsPage = () => {
  const navigate = useNavigate();
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [sports, setSports] = useState<Sport[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [brands, setBrands] = useState<Brand[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [sportFilter, setSportFilter] = useState('');

  useEffect(() => {
    Promise.all([
      catalogApi.getProducts(),
      catalogApi.getSports(),
      catalogApi.getCategories(),
      catalogApi.getBrands(),
    ])
      .then(([productsData, sportsData, categoriesData, brandsData]) => {
        setProducts(productsData);
        setSports(sportsData);
        setCategories(categoriesData);
        setBrands(brandsData);
      })
      .catch((err) => {
        setError(err instanceof AxiosError && err.response?.data?.message ? err.response.data.message : 'Failed to load products');
      })
      .finally(() => setLoading(false));
  }, []);

  const handleFilterChange = async (sportId: string) => {
    setSportFilter(sportId);
    setLoading(true);
    setError('');
    try {
      const data = await catalogApi.getProducts(sportId ? { sportId } : {});
      setProducts(data);
    } catch (err) {
      setError(err instanceof AxiosError && err.response?.data?.message ? err.response.data.message : 'Failed to filter products');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Products
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Box sx={{ display: 'flex', gap: 2, mb: 3 }}>
        <TextField
          select
          label="Sport"
          value={sportFilter}
          onChange={(e) => handleFilterChange(e.target.value)}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">All Sports</MenuItem>
          {sports.map((s) => (
            <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>
          ))}
        </TextField>
      </Box>

      {products.length === 0 ? (
        <Typography color="text.secondary">No products found.</Typography>
      ) : (
        <Grid container spacing={3}>
          {products.map((product) => (
            <Grid size={{ xs: 12, sm: 6, md: 4 }} key={product.id}>
              <Card>
                <CardActionArea onClick={() => navigate(`/products/${product.id}`)}>
                  <CardContent>
                    <Typography variant="h6" gutterBottom noWrap>
                      {product.name}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      SKU: {product.sku}
                    </Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
        </Grid>
      )}
    </Box>
  );
};

export default ProductsPage;
