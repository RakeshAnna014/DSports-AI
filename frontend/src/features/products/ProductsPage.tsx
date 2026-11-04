import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Pagination, Paper, Slider, Snackbar, Stack, Switch, TextField, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { SearchOffOutlined, SearchOutlined } from '@mui/icons-material';
import ProductCard from '@/components/ProductCard';
import ProductCardSkeleton from '@/components/ProductCardSkeleton';
import AiCommerceSearch from '@/features/ai/AiCommerceSearch';
import { ErrorState } from '@/components/common/States';
import { useAllProducts, useBrands, useCategories, useInventory, usePrices, useProductAttributes, useSports } from '@/hooks/useCatalog';
import { getApiErrorMessage } from '@/api/errors';
import { useCartStore } from '@/store/cartStore';
import { useAiStore } from '@/store/aiStore';
import { getProductImageUrl } from '@/lib/productUtils';
import { colors } from '@/design/tokens';
import type { InventoryRecord } from '@/types/catalog';

const PAGE_SIZE = 24;
const SKILLS = [
  { value: 'beginner', label: 'Beginner' },
  { value: 'intermediate', label: 'Intermediate' },
  { value: 'advanced', label: 'Professional' },
];
const SORTS = [
  { value: 'newest', label: 'Newest' },
  { value: 'price_asc', label: 'Price: Low to High' },
  { value: 'price_desc', label: 'Price: High to Low' },
  { value: 'discount', label: 'Biggest discount' },
  { value: 'name', label: 'Name A-Z' },
];

/**
 * The catalogue: the AI search on top, then ordinary deterministic browsing with filters (sport, category, brand, price, skill level,
 * availability), sorting and pagination. Filters never call the AI service; the two coexist on one page.
 */
const ProductsPage = () => {
  const navigate = useNavigate();
  const addToCart = useCartStore((s) => s.addItem);
  const askAboutProduct = useAiStore((s) => s.askAboutProduct);
  const [params, setParams] = useSearchParams();

  const [search, setSearch] = useState('');
  const sportId = params.get('sport') ?? '';
  const [categoryId, setCategoryId] = useState('');
  const [brandId, setBrandId] = useState('');
  const [skill, setSkill] = useState('');
  const [inStockOnly, setInStockOnly] = useState(false);
  const [sort, setSort] = useState('newest');
  const [range, setRange] = useState<[number, number] | null>(null);
  const [page, setPage] = useState(1);
  const [snackbar, setSnackbar] = useState<{ open: boolean; message: string; severity?: 'success' | 'error' }>({ open: false, message: '' });

  const { data: products = [], isLoading, isError, refetch } = useAllProducts();
  const { data: sports = [] } = useSports();
  const { data: categories = [] } = useCategories();
  const { data: brands = [] } = useBrands();
  const { data: prices = [] } = usePrices();
  const { data: inventory = [] } = useInventory();
  const { data: attributes = {} } = useProductAttributes();

  const priceById = useMemo(() => new Map(prices.map((p) => [p.productId, p])), [prices]);
  const brandName = useMemo(() => new Map(brands.map((b) => [b.id, b.name])), [brands]);
  const categoryName = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);
  const inventoryById = useMemo(() => {
    const m = new Map<string, InventoryRecord[]>();
    for (const i of inventory) m.set(i.productId, [...(m.get(i.productId) ?? []), i]);
    return m;
  }, [inventory]);
  const stockOf = useCallback((id: string) => (inventoryById.get(id) ?? []).reduce((s, r) => s + r.availableQuantity, 0), [inventoryById]);

  // Options are limited to what exists in the selected sport, so the filters never offer an empty result.
  const inSport = useMemo(() => products.filter((p) => !sportId || p.sportId === sportId), [products, sportId]);
  const categoryOptions = useMemo(() => categories.filter((c) => inSport.some((p) => p.categoryId === c.id)), [categories, inSport]);
  const brandOptions = useMemo(() => brands.filter((b) => inSport.some((p) => p.brandId === b.id)), [brands, inSport]);
  const bounds = useMemo((): [number, number] => {
    const values = inSport.map((p) => priceById.get(p.id)?.sellingPrice).filter((v): v is number => v !== undefined);
    return values.length ? [Math.floor(Math.min(...values) / 100) * 100, Math.ceil(Math.max(...values) / 100) * 100] : [0, 0];
  }, [inSport, priceById]);
  const activeRange: [number, number] = range ?? bounds;

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const out = inSport.filter((p) => {
      if (q && !`${p.name} ${brandName.get(p.brandId) ?? ''}`.toLowerCase().includes(q)) return false;
      if (categoryId && p.categoryId !== categoryId) return false;
      if (brandId && p.brandId !== brandId) return false;
      if (inStockOnly && stockOf(p.id) <= 0) return false;
      if (skill) {
        const level = attributes[p.id]?.skillLevel;
        if (level !== skill && level !== 'all_levels') return false;
      }
      const price = priceById.get(p.id)?.sellingPrice;
      if (range && price !== undefined && (price < range[0] || price > range[1])) return false;
      return true;
    });
    const sp = (id: string) => priceById.get(id)?.sellingPrice ?? Infinity;
    const disc = (id: string) => { const pr = priceById.get(id); return pr && pr.mrp > 0 ? (pr.mrp - pr.sellingPrice) / pr.mrp : 0; };
    if (sort === 'price_asc') out.sort((a, b) => sp(a.id) - sp(b.id));
    else if (sort === 'price_desc') out.sort((a, b) => (sp(b.id) === Infinity ? 0 : sp(b.id)) - (sp(a.id) === Infinity ? 0 : sp(a.id)));
    else if (sort === 'discount') out.sort((a, b) => disc(b.id) - disc(a.id));
    else if (sort === 'name') out.sort((a, b) => a.name.localeCompare(b.name));
    else out.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    return out;
  }, [inSport, search, categoryId, brandId, inStockOnly, skill, attributes, priceById, range, sort, brandName, stockOf]);

  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const shown = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  useEffect(() => { setRange(null); }, [sportId]);
  useEffect(() => { setPage(1); }, [search, sportId, categoryId, brandId, skill, inStockOnly, sort, range]);

  const chooseSport = (id: string) => {
    setCategoryId(''); setBrandId(''); setRange(null);
    const next = new URLSearchParams(params);
    if (id) next.set('sport', id); else next.delete('sport');
    setParams(next, { replace: true });
  };
  const clearFilters = () => { setSearch(''); setCategoryId(''); setBrandId(''); setSkill(''); setInStockOnly(false); setRange(null); chooseSport(''); };

  const handleAddToCart = useCallback(async (productId: string, name: string) => {
    try {
      await addToCart({ productId, name, price: priceById.get(productId)?.sellingPrice ?? 0, imageUrl: getProductImageUrl(name) });
      setSnackbar({ open: true, message: `✓ ${name} added to cart`, severity: 'success' });
    } catch (err) {
      setSnackbar({ open: true, message: getApiErrorMessage(err, 'Could not add to cart'), severity: 'error' });
    }
  }, [addToCart, priceById]);

  return (
    <Box>
      <Typography variant="h3" component="h1" sx={{ mb: 0.5 }}>
        Products {!isLoading && <Typography component="span" variant="body2" color="text.secondary" sx={{ ml: 1, fontWeight: 400 }}>({filtered.length})</Typography>}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Browse every sport, or ask DSports AI in your own words.</Typography>

      <AiCommerceSearch variant="compact" />

      <Box role="tablist" aria-label="Sports" data-testid="sport-tabs" sx={{ display: 'flex', gap: 1, overflowX: 'auto', pb: 1, mb: 1.5, scrollbarWidth: 'thin' }}>
        <Chip label="All sports" clickable color={sportId ? 'default' : 'primary'} onClick={() => chooseSport('')} role="tab" aria-selected={!sportId} />
        {sports.map((s) => (
          <Chip key={s.id} label={s.name} clickable color={sportId === s.id ? 'primary' : 'default'} onClick={() => chooseSport(s.id)} role="tab" aria-selected={sportId === s.id} />
        ))}
      </Box>

      <Paper variant="outlined" sx={{ p: 1.5, mb: 3, borderColor: colors.line }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ flexWrap: 'wrap', rowGap: 1.5 }} alignItems={{ md: 'center' }}>
          <TextField placeholder="Search products..." value={search} onChange={(e) => setSearch(e.target.value)} size="small" sx={{ minWidth: 220, flexGrow: 1 }}
            slotProps={{ input: { startAdornment: <SearchOutlined fontSize="small" sx={{ mr: 1 }} /> } }} />
          <TextField select label="Category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)} size="small" sx={{ minWidth: 150 }}>
            <MenuItem value="">All categories</MenuItem>
            {categoryOptions.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
          </TextField>
          <TextField select label="Brand" value={brandId} onChange={(e) => setBrandId(e.target.value)} size="small" sx={{ minWidth: 150 }}>
            <MenuItem value="">All brands</MenuItem>
            {brandOptions.map((b) => <MenuItem key={b.id} value={b.id}>{b.name}</MenuItem>)}
          </TextField>
          <TextField select label="Sort" value={sort} onChange={(e) => setSort(e.target.value)} size="small" sx={{ minWidth: 170 }}>
            {SORTS.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
          </TextField>
        </Stack>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} alignItems={{ md: 'center' }} sx={{ mt: 1.5 }}>
          <Box sx={{ minWidth: 260, flexGrow: 1, px: 1 }}>
            <Typography variant="caption" color="text.secondary">Price: ₹{activeRange[0].toLocaleString('en-IN')} — ₹{activeRange[1].toLocaleString('en-IN')}</Typography>
            <Slider size="small" value={activeRange} min={bounds[0]} max={bounds[1]} step={100} disabled={bounds[0] === bounds[1]} aria-label="Price range" data-testid="price-slider"
              onChange={(_, v) => setRange(v as [number, number])} valueLabelDisplay="auto" valueLabelFormat={(v) => `₹${v.toLocaleString('en-IN')}`} />
          </Box>
          <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexWrap: 'wrap', rowGap: 0.75 }} data-testid="skill-filter">
            <Typography variant="caption" color="text.secondary">Skill</Typography>
            {SKILLS.map((s) => <Chip key={s.value} size="small" label={s.label} clickable color={skill === s.value ? 'primary' : 'default'} onClick={() => setSkill(skill === s.value ? '' : s.value)} />)}
          </Stack>
          <FormControlLabel control={<Switch size="small" checked={inStockOnly} onChange={(e) => setInStockOnly(e.target.checked)} />} label="In stock only" />
          <Button size="small" onClick={clearFilters}>Reset</Button>
        </Stack>
      </Paper>

      {isError ? (
        <ErrorState body="The catalogue could not be loaded right now." onRetry={() => void refetch()} />
      ) : isLoading ? (
        <Grid container spacing={3}>{Array.from({ length: 8 }).map((_, i) => <Grid key={i} size={{ xs: 6, sm: 4, md: 3 }}><ProductCardSkeleton /></Grid>)}</Grid>
      ) : shown.length === 0 ? (
        <Stack alignItems="center" sx={{ py: 8 }} spacing={2} data-testid="no-products">
          <SearchOffOutlined sx={{ fontSize: 64, color: 'text.disabled' }} />
          <Typography variant="h6" color="text.secondary">No products found</Typography>
          <Typography variant="body2" color="text.disabled">Try adjusting your search or filters</Typography>
          <Button onClick={clearFilters}>Clear all filters</Button>
        </Stack>
      ) : (
        <>
          <Grid container spacing={{ xs: 1.5, md: 3 }} data-testid="product-grid">
            {shown.map((product) => (
              <Grid key={product.id} size={{ xs: 6, sm: 4, md: 3 }}>
                <ProductCard product={product} brandName={brandName.get(product.brandId) ?? ''} categoryName={categoryName.get(product.categoryId) ?? ''}
                  price={priceById.get(product.id)} inventory={inventoryById.get(product.id) ?? []}
                  onViewDetails={() => navigate(`/products/${product.id}`)} onAddToCart={() => handleAddToCart(product.id, product.name)}
                  onAskAi={() => askAboutProduct({ productId: product.id, name: product.name })} />
              </Grid>
            ))}
          </Grid>
          {pages > 1 && (
            <Stack alignItems="center" sx={{ mt: 4 }}>
              <Pagination count={pages} page={page} onChange={(_, p) => { setPage(p); window.scrollTo({ top: 0, behavior: 'smooth' }); }} color="primary" shape="rounded" data-testid="pagination" />
            </Stack>
          )}
        </>
      )}

      <Snackbar open={snackbar.open} autoHideDuration={3000} onClose={() => setSnackbar((p) => ({ ...p, open: false }))} anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        <Alert severity={snackbar.severity ?? 'success'} variant="filled" onClose={() => setSnackbar((p) => ({ ...p, open: false }))}>{snackbar.message}</Alert>
      </Snackbar>
    </Box>
  );
};

export default ProductsPage;
