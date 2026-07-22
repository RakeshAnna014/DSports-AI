import { useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import {
  Box,
  Typography,
  CircularProgress,
  Alert,
  Button,
  ButtonGroup,
  Chip,
  Divider,
  Table,
  TableBody,
  TableCell,
  TableRow,
  Snackbar,
  Stack,
  Breadcrumbs,
  Link,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  ShoppingCartOutlined,
  ShoppingCartCheckoutOutlined,
  BoltOutlined,
  ArrowBack,
  HomeOutlined,
} from '@mui/icons-material';
import ImageGallery from '@/components/ImageGallery';
import ProductCard from '@/components/ProductCard';
import ProductCardSkeleton from '@/components/ProductCardSkeleton';
import {
  useProductDetail,
  useProducts,
  usePrices,
  useInventory,
  useBrands,
  useCategories,
} from '@/hooks/useCatalog';
import { useCartStore } from '@/store/cartStore';
import {
  formatPrice,
  computeDiscount,
  computeStockLevel,
  getProductImageUrl,
} from '@/lib/productUtils';

const ProductDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const addToCart = useCartStore((s) => s.addItem);

  const [quantity, setQuantity] = useState(1);
  const [snackbar, setSnackbar] = useState<{ open: boolean; message: string }>({
    open: false,
    message: '',
  });

  const {
    data: product,
    isLoading: productLoading,
    isError: productError,
    error: productErr,
  } = useProductDetail(id ?? '');
  const { data: prices = [] } = usePrices(id);
  const { data: inventory = [] } = useInventory(id);
  const { data: brands = [] } = useBrands();
  const { data: categories = [] } = useCategories();
  const brandMap = useMemo(() => new Map(brands.map((b) => [b.id, b.name])), [brands]);
  const catMap = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);

  const price = prices[0];
  const inventoryRecords = inventory;
  const stock = computeStockLevel(inventoryRecords);
  const discount = price ? computeDiscount(price.mrp, price.sellingPrice) : 0;
  const isOutOfStock = stock.level === 'out_of_stock';
  const brandName = product ? brandMap.get(product.brandId) ?? '' : '';
  const categoryName = product ? catMap.get(product.categoryId) ?? '' : '';

  const { data: relatedProducts = [] } = useProducts({
    categoryId: product?.categoryId,
    size: 5,
  });
  const related = useMemo(
    () => relatedProducts.filter((p) => p.id !== id).slice(0, 4),
    [relatedProducts, id],
  );

  const handleAddToCart = () => {
    if (!product) return;
    const sellingPrice = price?.sellingPrice ?? 0;
    for (let i = 0; i < quantity; i++) {
      addToCart({
        productId: product.id,
        name: product.name,
        price: sellingPrice,
        imageUrl: getProductImageUrl(
          product.name,
          product.images?.map((img) => ({ url: img.url, primary: img.primary })),
        ),
      });
    }
    setSnackbar({ open: true, message: `${quantity} × ${product.name} added to cart` });
  };

  const handleBuyNow = () => {
    handleAddToCart();
    navigate('/cart');
  };

  if (!id) {
    return (
      <Box>
        <Alert severity="warning">Product ID is missing.</Alert>
        <Button variant="outlined" onClick={() => navigate('/products')} sx={{ mt: 2 }}>
          Back to Products
        </Button>
      </Box>
    );
  }

  if (productLoading) {
    return (
      <Box>
        <Button
          variant="text"
          startIcon={<ArrowBack />}
          onClick={() => navigate('/products')}
          sx={{ mb: 2 }}
        >
          Back to Products
        </Button>
        <Grid container spacing={4}>
          <Grid size={{ xs: 12, md: 6 }}>
            <CircularProgress />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <Stack spacing={2}>
              <CircularProgress size={20} />
              <CircularProgress size={16} />
              <CircularProgress size={40} />
            </Stack>
          </Grid>
        </Grid>
      </Box>
    );
  }

  if (productError || !product) {
    const message =
      productErr instanceof AxiosError && productErr.response?.data?.message
        ? productErr.response.data.message
        : 'Product not found.';
    return (
      <Box>
        <Button
          variant="text"
          startIcon={<ArrowBack />}
          onClick={() => navigate('/products')}
          sx={{ mb: 2 }}
        >
          Back to Products
        </Button>
        <Alert severity="error">{message}</Alert>
      </Box>
    );
  }

  return (
    <Box>
      <Breadcrumbs sx={{ mb: 2 }}>
        <Link
          underline="hover"
          color="inherit"
          sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.5 }}
          onClick={() => navigate('/')}
        >
          <HomeOutlined fontSize="small" />
          Home
        </Link>
        <Link
          underline="hover"
          color="inherit"
          sx={{ cursor: 'pointer' }}
          onClick={() => navigate('/products')}
        >
          Products
        </Link>
        <Typography color="text.primary">
          {product.name.length > 30
            ? `${product.name.slice(0, 30)}...`
            : product.name}
        </Typography>
      </Breadcrumbs>

      <Grid container spacing={4}>
        <Grid size={{ xs: 12, md: 6 }}>
          <ImageGallery
            productName={product.name}
            images={product.images?.map((img) => ({
              url: img.url,
              primary: img.primary,
            }))}
          />
        </Grid>

        <Grid size={{ xs: 12, md: 6 }}>
          {brandName && (
            <Typography variant="subtitle2" color="text.secondary" gutterBottom>
              {brandName}
            </Typography>
          )}
          <Typography variant="h4" fontWeight={700} gutterBottom>
            {product.name}
          </Typography>
          <Typography variant="body2" color="text.disabled" gutterBottom>
            SKU: {product.sku}
          </Typography>

          {categoryName && (
            <Chip
              label={categoryName}
              size="small"
              variant="outlined"
              sx={{ mt: 1 }}
            />
          )}

          <Box sx={{ mt: 3, mb: 2 }}>
            {price && (
              <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5 }}>
                <Typography variant="h3" color="primary.main" fontWeight={700}>
                  {formatPrice(price.sellingPrice, price.currency)}
                </Typography>
                {price.mrp > price.sellingPrice && (
                  <>
                    <Typography
                      variant="h6"
                      color="text.disabled"
                      sx={{ textDecoration: 'line-through' }}
                    >
                      {formatPrice(price.mrp, price.currency)}
                    </Typography>
                    <Chip
                      label={`-${discount}%`}
                      color="error"
                      size="small"
                      sx={{ fontWeight: 700 }}
                    />
                  </>
                )}
              </Box>
            )}
          </Box>

          <Box sx={{ mb: 3 }}>
            <Chip
              label={stock.label}
              variant="filled"
              color={
                stock.level === 'in_stock'
                  ? 'success'
                  : stock.level === 'low_stock'
                    ? 'warning'
                    : 'error'
              }
              sx={{ fontWeight: 600 }}
            />
          </Box>

          {!isOutOfStock && (
            <Box sx={{ mb: 3 }}>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Quantity
              </Typography>
              <ButtonGroup size="small">
                <Button
                  onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                  disabled={quantity <= 1}
                  aria-label="Decrease quantity"
                >
                  -
                </Button>
                <Button disabled sx={{ px: 3, fontWeight: 700 }}>
                  {quantity}
                </Button>
                <Button
                  onClick={() => setQuantity((q) => q + 1)}
                  aria-label="Increase quantity"
                >
                  +
                </Button>
              </ButtonGroup>
            </Box>
          )}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 4 }}>
            <Button
              variant="contained"
              size="large"
              startIcon={<ShoppingCartOutlined />}
              disabled={isOutOfStock}
              onClick={handleAddToCart}
              fullWidth
            >
              {isOutOfStock ? 'Out of Stock' : 'Add to Cart'}
            </Button>
            <Button
              variant="outlined"
              size="large"
              color="secondary"
              startIcon={<BoltOutlined />}
              disabled={isOutOfStock}
              onClick={handleBuyNow}
              fullWidth
            >
              Buy Now
            </Button>
          </Stack>

          {product.description && (
            <Box sx={{ mb: 3 }}>
              <Typography variant="h6" fontWeight={600} gutterBottom>
                Description
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {product.description}
              </Typography>
            </Box>
          )}

          <Divider sx={{ mb: 3 }} />

          <Typography variant="h6" fontWeight={600} gutterBottom>
            Specifications
          </Typography>
          <Table size="small">
            <TableBody>
              {product.weight != null && (
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, color: 'text.secondary', pl: 0 }}>
                    Weight
                  </TableCell>
                  <TableCell>
                    {product.weight} {product.weightUnit ?? ''}
                  </TableCell>
                </TableRow>
              )}
              {product.length != null && (
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, color: 'text.secondary', pl: 0 }}>
                    Dimensions
                  </TableCell>
                  <TableCell>
                    {product.length} × {product.width ?? '—'} × {product.height ?? '—'}{' '}
                    {product.dimensionUnit ?? ''}
                  </TableCell>
                </TableRow>
              )}
              <TableRow>
                <TableCell sx={{ fontWeight: 600, color: 'text.secondary', pl: 0 }}>
                  Category
                </TableCell>
                <TableCell>{categoryName || '—'}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell sx={{ fontWeight: 600, color: 'text.secondary', pl: 0 }}>
                  Brand
                </TableCell>
                <TableCell>{brandName || '—'}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell sx={{ fontWeight: 600, color: 'text.secondary', pl: 0 }}>
                  Status
                </TableCell>
                <TableCell>
                  <Chip
                    label={product.status}
                    size="small"
                    color={product.status === 'ACTIVE' ? 'success' : 'default'}
                    variant="outlined"
                  />
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Grid>
      </Grid>

      {related.length > 0 && (
        <Box sx={{ mt: 6 }}>
          <Divider sx={{ mb: 3 }} />
          <Typography variant="h5" fontWeight={600} gutterBottom>
            Related Products
          </Typography>
          <Grid container spacing={3}>
            {related.map((p) => (
              <Grid key={p.id} size={{ xs: 12, sm: 6, md: 3 }}>
                <ProductCard
                  product={p}
                  onViewDetails={() => navigate(`/products/${p.id}`)}
                  onAddToCart={() => {
                    const relatedPrice = prices.find((pr) => pr.productId === p.id);
                    addToCart({
                      productId: p.id,
                      name: p.name,
                      price: relatedPrice?.sellingPrice ?? 0,
                      imageUrl: getProductImageUrl(p.name),
                    });
                    setSnackbar({ open: true, message: `${p.name} added to cart` });
                  }}
                />
              </Grid>
            ))}
          </Grid>
        </Box>
      )}

      <Snackbar
        open={snackbar.open}
        autoHideDuration={3000}
        onClose={() => setSnackbar((prev) => ({ ...prev, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity="success"
          variant="filled"
          icon={<ShoppingCartCheckoutOutlined fontSize="small" />}
          onClose={() => setSnackbar((prev) => ({ ...prev, open: false }))}
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Box>
  );
};

export default ProductDetailPage;
