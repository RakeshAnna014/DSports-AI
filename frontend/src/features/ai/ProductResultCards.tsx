import { useState } from 'react';
import { Box, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { AutoAwesome, Favorite, FavoriteBorder } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useCartStore } from '@/store/cartStore';
import { useAuthStore } from '@/store/authStore';
import { useAiStore } from '@/store/aiStore';
import { useUiStore } from '@/store/uiStore';
import { useWishlistStore } from '@/store/wishlistStore';
import { formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { getApiErrorMessage } from '@/api/errors';
import { colors, motion, shadows } from '@/design/tokens';
import type { AiProduct } from '@/types/ai';

const label = (value: string) => value.replace(/_/g, ' ');
const SHOWN_ATTRIBUTES = ['skillLevel', 'weightClass', 'material', 'surface'];

/** One verified product from DSports (live price, stock, rating). `grid` is the roomy page layout; the drawer uses the compact row. */
const ProductResultCard = ({ product, onNavigate, grid }: { product: AiProduct; onNavigate: () => void; grid: boolean }) => {
  const navigate = useNavigate();
  const addItem = useCartStore((s) => s.addItem);
  const noteAdded = useUiStore((s) => s.noteAdded);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const askAboutProduct = useAiStore((s) => s.askAboutProduct);
  const wished = useWishlistStore((s) => s.items.find((i) => i.productId === product.product_id));
  const addWish = useWishlistStore((s) => s.addItem);
  const removeWish = useWishlistStore((s) => s.removeItem);
  const [added, setAdded] = useState<string | null>(null);
  const outOfStock = product.in_stock === false;
  const save = product.mrp && product.price !== undefined && product.mrp > product.price ? product.mrp - product.price : 0;
  const pct = save && product.mrp ? Math.round((save / product.mrp) * 100) : 0;

  const add = async () => {
    try {
      await addItem({ productId: product.product_id, name: product.name, price: product.price ?? 0, imageUrl: product.image_url ?? '' }, 1);
      noteAdded({ productId: product.product_id, name: product.name });
      setAdded('✓ Added to cart');
    } catch (err) {
      setAdded(getApiErrorMessage(err, 'Could not add to cart'));
    }
  };
  const toggleWish = async () => {
    if (!isAuthenticated) { onNavigate(); navigate('/login'); return; }
    if (wished) await removeWish(wished.itemId); else await addWish(product.product_id);
  };

  return (
    <Paper variant="outlined" data-testid="ai-product-card"
      sx={{ display: 'flex', flexDirection: grid ? 'column' : 'row', overflow: 'hidden', borderColor: colors.line, mb: grid ? 0 : 1, bgcolor: 'background.paper',
        transition: `box-shadow ${motion.base}, transform ${motion.base}`, '&:hover': { boxShadow: shadows.cardHover, transform: 'translateY(-2px)' } }}>
      <Box sx={{ position: 'relative', width: grid ? '100%' : 104, flexShrink: 0 }}>
        <ProductImage src={product.image_url} productId={product.product_id} alt={product.name} aspectRatio={grid ? '4 / 3' : '1 / 1'} />
        {pct > 0 && <Chip size="small" label={`${pct}% OFF`} sx={{ position: 'absolute', top: 8, left: 8, bgcolor: 'error.main', color: '#fff', fontWeight: 800, fontSize: '0.7rem' }} />}
        <Button size="small" onClick={toggleWish} aria-label={wished ? 'Remove from wishlist' : 'Add to wishlist'}
          sx={{ position: 'absolute', top: 4, right: 4, minWidth: 0, p: 0.5, borderRadius: '50%', bgcolor: 'rgba(255,255,255,.92)', '&:hover': { bgcolor: '#fff' } }}>
          {wished ? <Favorite fontSize="small" color="error" /> : <FavoriteBorder fontSize="small" />}
        </Button>
      </Box>
      <Box sx={{ p: 1.5, flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '.04em', fontWeight: 700 }}>
          {[product.brand, product.category].filter(Boolean).join(' · ')}
        </Typography>
        <Typography variant="subtitle2" fontWeight={800} title={product.name} noWrap={!grid}>{product.name}</Typography>
        {product.rating && product.rating.count > 0 && (
          <Typography variant="caption" color="text.secondary"><Box component="span" sx={{ color: '#F5A524' }}>★</Box> {product.rating.average.toFixed(1)} ({product.rating.count} reviews)</Typography>
        )}
        <Stack direction="row" spacing={1} alignItems="baseline" sx={{ mt: 0.5, flexWrap: 'wrap' }}>
          {product.price !== undefined && <Typography variant="h6" color="primary.dark" fontWeight={800}>{formatPrice(product.price, product.currency)}</Typography>}
          {save > 0 && <Typography variant="body2" sx={{ textDecoration: 'line-through' }} color="text.disabled">{formatPrice(product.mrp!, product.currency)}</Typography>}
          {save > 0 && <Typography variant="caption" color="success.main" fontWeight={800}>SAVE {formatPrice(save, product.currency)}</Typography>}
        </Stack>
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5, my: 0.75 }}>
          <Chip size="small" color={outOfStock ? 'error' : 'success'} variant="outlined"
            label={outOfStock ? '✕ Out of stock' : product.available_quantity !== undefined && product.available_quantity <= 5 ? `Only ${product.available_quantity} left` : '✓ In stock'} />
          {product.over_budget && <Chip size="small" color="warning" label="Above your budget" />}
          {SHOWN_ATTRIBUTES.filter((k) => product.attributes?.[k]).map((k) => <Chip key={k} size="small" variant="outlined" label={label(product.attributes![k])} />)}
          {product.weight_g != null && <Chip size="small" variant="outlined" label={product.weight_g >= 1000 ? `${product.weight_g / 1000} kg` : `${product.weight_g} g`} />}
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 'auto', flexWrap: 'wrap', rowGap: 0.5 }}>
          <Button size="small" variant="outlined" onClick={() => { onNavigate(); navigate(`/products/${product.product_id}`); }}>View</Button>
          <Button size="small" variant="contained" disabled={outOfStock} onClick={add}>Add to cart</Button>
          <Button size="small" color="secondary" startIcon={<AutoAwesome fontSize="small" />} data-testid="ai-ask-about"
            onClick={() => { askAboutProduct({ productId: product.product_id, name: product.name }); onNavigate(); }}>Ask AI</Button>
          {added && <Typography variant="caption" color="text.secondary" role="status">{added}</Typography>}
        </Stack>
      </Box>
    </Paper>
  );
};

const ProductResultCards = ({ products, onNavigate, grid = false }: { products: AiProduct[]; onNavigate: () => void; grid?: boolean }) => (
  <Box sx={grid ? { display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: `repeat(${Math.min(Math.max(products.length, 1), 3)}, 1fr)` } } : undefined}>
    {products.map((p) => <ProductResultCard key={p.product_id} product={p} onNavigate={onNavigate} grid={grid} />)}
  </Box>
);

export default ProductResultCards;
