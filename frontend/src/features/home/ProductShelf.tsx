import { useMemo, useState, type ReactNode } from 'react';
import { Alert, Box, Button, Snackbar, Stack, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useNavigate } from 'react-router-dom';
import ProductCard from '@/components/ProductCard';
import ProductCardSkeleton from '@/components/ProductCardSkeleton';
import { useAllProducts, useBrands, useCategories, useInventory, usePrices } from '@/hooks/useCatalog';
import { useCartStore } from '@/store/cartStore';
import { useAiStore } from '@/store/aiStore';
import { useUiStore } from '@/store/uiStore';
import { getApiErrorMessage } from '@/api/errors';
import { getProductImageUrl } from '@/lib/productUtils';
import type { InventoryRecord, Price, ProductSummary } from '@/types/catalog';

export interface ShelfData {
  products: ProductSummary[];
  priceById: Map<string, Price>;
}

/** Round-robin across sports (at most `perSport` each) so a shelf shows the range of the shop, not one sport. */
export const diversify = (items: ProductSummary[], perSport = 1): ProductSummary[] => {
  const groups = new Map<string, ProductSummary[]>();
  for (const p of items) groups.set(p.sportId, [...(groups.get(p.sportId) ?? []), p]);
  const out: ProductSummary[] = [];
  for (let round = 0; round < perSport; round++) for (const list of groups.values()) if (list[round]) out.push(list[round]);
  return out;
};

interface Props {
  title: string;
  subtitle?: string;
  testId?: string;
  limit?: number;
  action?: { label: string; to: string };
  /** Chooses and orders the products from the live catalogue (never a hard-coded list). */
  pick: (data: ShelfData) => ProductSummary[];
  empty?: ReactNode;
}

/** A row of real catalogue products with live price, stock and discount, the normal Add to Cart and Ask AI. */
const ProductShelf = ({ title, subtitle, testId, limit = 4, action, pick, empty = null }: Props) => {
  const navigate = useNavigate();
  const addToCart = useCartStore((s) => s.addItem);
  const askAboutProduct = useAiStore((s) => s.askAboutProduct);
  const noteAdded = useUiStore((s) => s.noteAdded);
  const { data: products, isLoading, isError } = useAllProducts();
  const { data: prices = [] } = usePrices();
  const { data: inventory = [] } = useInventory();
  const { data: brands = [] } = useBrands();
  const { data: categories = [] } = useCategories();
  const [snack, setSnack] = useState<{ message: string; severity: 'success' | 'error' } | null>(null);

  const priceById = useMemo(() => new Map(prices.map((p) => [p.productId, p])), [prices]);
  const brandName = useMemo(() => new Map(brands.map((b) => [b.id, b.name])), [brands]);
  const categoryName = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);
  const inventoryById = useMemo(() => {
    const m = new Map<string, InventoryRecord[]>();
    for (const i of inventory) m.set(i.productId, [...(m.get(i.productId) ?? []), i]);
    return m;
  }, [inventory]);
  const shown = useMemo(() => pick({ products: products ?? [], priceById }).slice(0, limit), [products, priceById, pick, limit]);

  const add = async (p: ProductSummary) => {
    try {
      await addToCart({ productId: p.id, name: p.name, price: priceById.get(p.id)?.sellingPrice ?? 0, imageUrl: getProductImageUrl(p.name) });
      noteAdded({ productId: p.id, name: p.name });
      setSnack({ message: `✓ ${p.name} added to cart`, severity: 'success' });
    } catch (err) {
      setSnack({ message: getApiErrorMessage(err, 'Could not add to cart'), severity: 'error' });
    }
  };

  if (!isLoading && !isError && shown.length === 0) return <>{empty}</>;
  return (
    <Box data-testid={testId} component="section" aria-label={title}>
      <Stack direction="row" alignItems="flex-end" justifyContent="space-between" sx={{ mb: 1.5 }}>
        <Box>
          <Typography variant="h5" fontWeight={800}>{title}</Typography>
          {subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography>}
        </Box>
        {action && <Button size="small" onClick={() => navigate(action.to)}>{action.label}</Button>}
      </Stack>
      {isError && <Alert severity="error">Products could not be loaded right now.</Alert>}
      <Grid container spacing={{ xs: 1.5, md: 2.5 }}>
        {isLoading
          ? Array.from({ length: limit }).map((_, i) => <Grid key={i} size={{ xs: 6, md: 3 }}><ProductCardSkeleton /></Grid>)
          : shown.map((p) => (
            <Grid key={p.id} size={{ xs: 6, md: 3 }}>
              <ProductCard product={p} brandName={brandName.get(p.brandId) ?? ''} categoryName={categoryName.get(p.categoryId) ?? ''}
                price={priceById.get(p.id)} inventory={inventoryById.get(p.id) ?? []}
                onViewDetails={() => navigate(`/products/${p.id}`)} onAddToCart={() => add(p)} onAskAi={() => askAboutProduct({ productId: p.id, name: p.name })} />
            </Grid>
          ))}
      </Grid>
      <Snackbar open={!!snack} autoHideDuration={3000} onClose={() => setSnack(null)} anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        {snack ? <Alert severity={snack.severity} variant="filled" onClose={() => setSnack(null)}>{snack.message}</Alert> : undefined}
      </Snackbar>
    </Box>
  );
};

export default ProductShelf;
