import { Box, Button, Card, CardActions, CardContent, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { AutoAwesome, Favorite, FavoriteBorder, ShoppingCartOutlined, VisibilityOutlined } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import type { InventoryRecord, Price, ProductSummary } from '@/types/catalog';
import { computeDiscount, computeStockLevel, formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { useWishlistStore } from '@/store/wishlistStore';
import { useAuthStore } from '@/store/authStore';
import { colors, motion, shadows } from '@/design/tokens';

interface ProductCardProps {
  product: ProductSummary;
  brandName?: string;
  categoryName?: string;
  price?: Price;
  inventory?: InventoryRecord[];
  /** Average rating and review count, when the caller has them (the catalogue list does not carry ratings). */
  rating?: { average: number; count: number };
  onViewDetails: () => void;
  onAddToCart?: () => void;
  /** Starts a question about this product in the AI search. */
  onAskAi?: () => void;
}

/** A premium product card: image, brand, price with real list-price discount and saving, stock, wishlist, View, Add to Cart, Ask AI. */
const ProductCard = ({ product, brandName, categoryName, price, inventory, rating, onViewDetails, onAddToCart, onAskAi }: ProductCardProps) => {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const wishlisted = useWishlistStore((s) => s.items.find((i) => i.productId === product.id));
  const addWish = useWishlistStore((s) => s.addItem);
  const removeWish = useWishlistStore((s) => s.removeItem);
  const stock = computeStockLevel(inventory);
  const discount = price ? computeDiscount(price.mrp, price.sellingPrice) : 0;
  const saving = price && price.mrp > price.sellingPrice ? price.mrp - price.sellingPrice : 0;
  const out = stock.level === 'out_of_stock';

  const toggleWish = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!isAuthenticated) { navigate('/login'); return; }
    if (wishlisted) await removeWish(wishlisted.itemId);
    else await addWish(product.id);
  };

  return (
    <Card tabIndex={0} aria-label={product.name}
      sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', border: `1px solid ${colors.line}`,
        '&:hover, &:focus-within': { transform: 'translateY(-4px)', boxShadow: shadows.cardHover, '& img': { transform: 'scale(1.04)' } },
        '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' } }}>
      <Box sx={{ position: 'relative', overflow: 'hidden' }}>
        <ProductImage src={product.imageUrl} productId={product.id} alt={product.name} />
        <Stack direction="row" spacing={0.5} sx={{ position: 'absolute', top: 8, left: 8 }}>
          {discount > 0 && <Chip label={`${discount}% OFF`} size="small" sx={{ bgcolor: 'error.main', color: '#fff', fontWeight: 800, fontSize: '0.7rem' }} />}
          {stock.level === 'low_stock' && <Chip label={stock.label} size="small" color="warning" sx={{ fontWeight: 700, fontSize: '0.7rem' }} />}
        </Stack>
        <Tooltip title={wishlisted ? 'Remove from wishlist' : 'Add to wishlist'}>
          <IconButton aria-label={wishlisted ? 'Remove from wishlist' : 'Add to wishlist'} onClick={toggleWish} size="small"
            sx={{ position: 'absolute', top: 6, right: 6, bgcolor: 'rgba(255,255,255,0.92)', transition: `transform ${motion.fast}`, '&:hover': { bgcolor: '#fff', transform: 'scale(1.12)' } }}>
            {wishlisted ? <Favorite fontSize="small" color="error" /> : <FavoriteBorder fontSize="small" />}
          </IconButton>
        </Tooltip>
      </Box>

      <CardContent sx={{ flexGrow: 1, pb: 0.5 }}>
        <Typography variant="caption" color="text.secondary" sx={{ letterSpacing: '.04em', textTransform: 'uppercase', fontWeight: 700 }}>
          {[brandName, categoryName].filter(Boolean).join(' · ')}
        </Typography>
        <Typography variant="subtitle2" fontWeight={700} lineHeight={1.3}
          sx={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', minHeight: 40, mt: 0.25 }}>
          {product.name}
        </Typography>
        {rating && rating.count > 0 && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            <Box component="span" sx={{ color: '#F5A524' }}>★</Box> {rating.average.toFixed(1)} ({rating.count})
          </Typography>
        )}
        {price && (
          <Box sx={{ mt: 0.75, display: 'flex', alignItems: 'baseline', gap: 1, flexWrap: 'wrap' }}>
            <Typography variant="h6" color="primary.dark" fontWeight={800}>{formatPrice(price.sellingPrice, price.currency)}</Typography>
            {saving > 0 && <Typography variant="body2" color="text.disabled" sx={{ textDecoration: 'line-through' }}>{formatPrice(price.mrp, price.currency)}</Typography>}
          </Box>
        )}
        {saving > 0 && <Typography variant="caption" sx={{ color: 'success.dark', fontWeight: 800 }}>SAVE {formatPrice(saving, price?.currency)}</Typography>}
        <Box sx={{ mt: 0.5 }}>
          <Chip label={out ? '✕ Out of stock' : `✓ ${stock.label}`} size="small" variant="outlined"
            color={stock.level === 'in_stock' ? 'success' : stock.level === 'low_stock' ? 'warning' : 'error'} sx={{ fontSize: '0.7rem' }} />
        </Box>
      </CardContent>

      <CardActions sx={{ p: 1.5, pt: 1, gap: 1, flexWrap: 'wrap' }}>
        <Button variant="outlined" size="small" startIcon={<VisibilityOutlined />} onClick={onViewDetails} sx={{ flex: 1 }}>View</Button>
        <Button variant="contained" size="small" disabled={out} sx={{ flex: 1.2 }} startIcon={<ShoppingCartOutlined fontSize="small" />}
          onClick={(e) => { e.stopPropagation(); onAddToCart?.(); }} aria-label={out ? 'Out of stock' : 'Add to cart'}>
          {out ? 'Sold out' : 'Add to Cart'}
        </Button>
        {onAskAi && (
          <Button size="small" color="secondary" startIcon={<AutoAwesome fontSize="small" />} data-testid="ask-ai-button"
            onClick={(e) => { e.stopPropagation(); onAskAi(); }} sx={{ flexBasis: '100%', ml: '0 !important', justifyContent: 'center' }}>
            Ask AI about this
          </Button>
        )}
      </CardActions>
    </Card>
  );
};

export default ProductCard;
