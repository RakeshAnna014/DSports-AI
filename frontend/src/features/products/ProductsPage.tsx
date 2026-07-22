import { useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import {
  Box,
  Typography,
  Alert,
  Snackbar,
  Stack,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  SearchOffOutlined,
  ShoppingCartCheckoutOutlined,
} from '@mui/icons-material';
import ProductCard from '@/components/ProductCard';
import ProductCardSkeleton from '@/components/ProductCardSkeleton';
import ProductFilters from '@/components/ProductFilters';
import {
  useProducts,
  useSports,
  useCategories,
  useBrands,
  usePrices,
  useInventory,
} from '@/hooks/useCatalog';
import { useCartStore } from '@/store/cartStore';
import { getProductImageUrl } from '@/lib/productUtils';
import type { Price, InventoryRecord } from '@/types/catalog';

interface EnrichedFields {
  brandName: string;
  categoryName: string;
  sportName: string;
  price: Price | undefined;
  inventoryRecords: InventoryRecord[];
}

const ProductsPage = () => {
  const navigate = useNavigate();
  const addToCart = useCartStore((s) => s.addItem);

  const [search, setSearch] = useState('');
  const [sportId, setSportId] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [brandId, setBrandId] = useState('');
  const [sort, setSort] = useState('newest');
  const [snackbar, setSnackbar] = useState<{ open: boolean; message: string }>({
    open: false,
    message: '',
  });

  const { data: products, isLoading: productsLoading, isError: productsError, error: productsErr } =
    useProducts({ size: 100 });
  const { data: sports = [] } = useSports();
  const { data: categories = [] } = useCategories();
  const { data: brands = [] } = useBrands();
  const { data: allPrices = [] } = usePrices();
  const { data: allInventory = [] } = useInventory();

  const sportMap = useMemo(
    () => new Map(sports.map((s) => [s.id, s.name])),
    [sports],
  );
  const catMap = useMemo(
    () => new Map(categories.map((c) => [c.id, c.name])),
    [categories],
  );
  const brandMap = useMemo(
    () => new Map(brands.map((b) => [b.id, b.name])),
    [brands],
  );

  const inventoryByProduct = useMemo(() => {
    const map = new Map<string, InventoryRecord[]>();
    for (const inv of allInventory) {
      const existing = map.get(inv.productId);
      if (existing) {
        existing.push(inv);
      } else {
        map.set(inv.productId, [inv]);
      }
    }
    return map;
  }, [allInventory]);

  const priceByProduct = useMemo(
    () => new Map(allPrices.map((p) => [p.productId, p])),
    [allPrices],
  );

  const enriched = useMemo((): (EnrichedFields & { id: string; createdAt: string })[] => {
    if (!products) return [];

    let filtered = [...products];

    if (search) {
      const q = search.toLowerCase();
      filtered = filtered.filter((p) => p.name.toLowerCase().includes(q));
    }
    if (sportId) filtered = filtered.filter((p) => p.sportId === sportId);
    if (categoryId) filtered = filtered.filter((p) => p.categoryId === categoryId);
    if (brandId) filtered = filtered.filter((p) => p.brandId === brandId);

    const withPrices = filtered.map((p) => ({
      id: p.id,
      createdAt: p.createdAt,
      brandName: brandMap.get(p.brandId) ?? '',
      categoryName: catMap.get(p.categoryId) ?? '',
      sportName: sportMap.get(p.sportId) ?? '',
      price: priceByProduct.get(p.id),
      inventoryRecords: inventoryByProduct.get(p.id) ?? [],
    }));

    if (sort === 'newest') {
      withPrices.sort(
        (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      );
    } else if (sort === 'price_asc') {
      withPrices.sort(
        (a, b) => (a.price?.sellingPrice ?? Infinity) - (b.price?.sellingPrice ?? Infinity),
      );
    } else if (sort === 'price_desc') {
      withPrices.sort(
        (a, b) => (b.price?.sellingPrice ?? 0) - (a.price?.sellingPrice ?? 0),
      );
    }

    return withPrices;
  }, [
    products,
    search,
    sportId,
    categoryId,
    brandId,
    sort,
    brandMap,
    catMap,
    sportMap,
    priceByProduct,
    inventoryByProduct,
  ]);

  const clearFilters = useCallback(() => {
    setSearch('');
    setSportId('');
    setCategoryId('');
    setBrandId('');
  }, []);

  const handleAddToCart = useCallback(
    (productId: string, name: string) => {
      const price = priceByProduct.get(productId);
      addToCart({
        productId,
        name,
        price: price?.sellingPrice ?? 0,
        imageUrl: getProductImageUrl(name),
      });
      setSnackbar({ open: true, message: `${name} added to cart` });
    },
    [addToCart, priceByProduct],
  );

  if (productsLoading) {
    return (
      <Box>
        <Typography variant="h4" fontWeight={700} gutterBottom>
          Products
        </Typography>
        <Grid container spacing={3}>
          {Array.from({ length: 8 }).map((_, i) => (
            <Grid key={i} size={{ xs: 12, sm: 6, md: 3 }}>
              <ProductCardSkeleton />
            </Grid>
          ))}
        </Grid>
      </Box>
    );
  }

  if (productsError) {
    const message =
      productsErr instanceof AxiosError && productsErr.response?.data?.message
        ? productsErr.response.data.message
        : 'Unable to load products. Please try again later.';
    return (
      <Box>
        <Typography variant="h4" fontWeight={700} gutterBottom>
          Products
        </Typography>
        <Alert severity="error" sx={{ mb: 2 }}>
          {message}
        </Alert>
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h4" fontWeight={700} gutterBottom>
        Products
        {enriched.length > 0 && (
          <Typography
            component="span"
            variant="body2"
            color="text.secondary"
            sx={{ ml: 1, fontWeight: 400 }}
          >
            ({enriched.length})
          </Typography>
        )}
      </Typography>

      <ProductFilters
        search={search}
        onSearchChange={setSearch}
        sportId={sportId}
        onSportChange={setSportId}
        categoryId={categoryId}
        onCategoryChange={setCategoryId}
        brandId={brandId}
        onBrandChange={setBrandId}
        sort={sort}
        onSortChange={setSort}
        sports={sports}
        categories={categories}
        brands={brands}
      />

      {enriched.length === 0 ? (
        <Stack alignItems="center" sx={{ py: 8 }} spacing={2}>
          <SearchOffOutlined sx={{ fontSize: 64, color: 'text.disabled' }} />
          <Typography variant="h6" color="text.secondary">
            No products found
          </Typography>
          <Typography variant="body2" color="text.disabled">
            Try adjusting your search or filters
          </Typography>
          {(search || sportId || categoryId || brandId) && (
            <Typography
              variant="body2"
              color="primary"
              sx={{ cursor: 'pointer', textDecoration: 'underline' }}
              onClick={clearFilters}
            >
              Clear all filters
            </Typography>
          )}
        </Stack>
      ) : (
        <Grid container spacing={3}>
          {products?.map((product) => {
            const fields = enriched.find((e) => e.id === product.id);
            if (!fields) return null;
            return (
              <Grid key={product.id} size={{ xs: 12, sm: 6, md: 3 }}>
                <ProductCard
                  product={product}
                  brandName={fields.brandName}
                  categoryName={fields.categoryName}
                  price={fields.price}
                  inventory={fields.inventoryRecords}
                  onViewDetails={() => navigate(`/products/${product.id}`)}
                  onAddToCart={() => handleAddToCart(product.id, product.name)}
                />
              </Grid>
            );
          })}
        </Grid>
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

export default ProductsPage;
