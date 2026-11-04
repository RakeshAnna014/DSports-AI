import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Paper, Snackbar, Stack, Typography } from '@mui/material';
import { AutoAwesome, CheckCircleOutline } from '@mui/icons-material';
import { aiApi } from '@/api/ai';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';
import { useAiStore } from '@/store/aiStore';
import { useUiStore } from '@/store/uiStore';
import { getApiErrorMessage } from '@/api/errors';
import { formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { colors, motion, shadows } from '@/design/tokens';
import { ProductRowSkeleton } from '@/components/common/States';
import type { AiRecommendationItem, AiRecommendations } from '@/types/ai';

interface Props {
  /** Products in the cart or being viewed. Empty = personalise from the customer's own order history (signed-in only). */
  productIds?: string[];
  title?: string;
  limit?: number;
  compact?: boolean;
  /** Shown instead when there is nothing to recommend (for example a guest with no product in context). */
  fallback?: ReactNode;
}

const RecommendationCard = ({ item, compact, onAdd }: { item: AiRecommendationItem; compact: boolean; onAdd: (i: AiRecommendationItem) => void }) => {
  const askAboutProduct = useAiStore((s) => s.askAboutProduct);
  const save = item.mrp && item.price !== undefined && item.mrp > item.price ? item.mrp - item.price : 0;
  const pct = save && item.mrp ? Math.round((save / item.mrp) * 100) : 0;
  return (
    <Paper variant="outlined" data-testid="recommendation-card"
      sx={{ display: 'flex', flexDirection: compact ? 'row' : 'column', overflow: 'hidden', borderColor: colors.line, transition: `box-shadow ${motion.base}, transform ${motion.base}`,
        '&:hover': { boxShadow: shadows.cardHover, transform: 'translateY(-2px)' } }}>
      <Box sx={{ position: 'relative', width: compact ? 96 : '100%', flexShrink: 0 }}>
        <ProductImage src={item.image_url} productId={item.product_id} alt={item.name} aspectRatio={compact ? '1 / 1' : '4 / 3'} />
        {pct > 0 && <Chip size="small" label={`${pct}% OFF`} sx={{ position: 'absolute', top: 8, left: 8, bgcolor: 'error.main', color: '#fff', fontWeight: 800 }} />}
      </Box>
      <Box sx={{ p: 1.5, display: 'flex', flexDirection: 'column', gap: 0.5, flexGrow: 1, minWidth: 0 }}>
        <Typography variant="caption" color="text.secondary">{item.brand}</Typography>
        <Typography variant="subtitle2" fontWeight={700} noWrap title={item.name}>{item.name}</Typography>
        <Stack direction="row" spacing={1} alignItems="baseline">
          <Typography variant="subtitle1" color="primary.dark" fontWeight={800}>{formatPrice(item.price ?? 0)}</Typography>
          {save > 0 && <Typography variant="caption" sx={{ textDecoration: 'line-through' }} color="text.disabled">{formatPrice(item.mrp!)}</Typography>}
          {save > 0 && <Typography variant="caption" color="success.main" fontWeight={700}>Save {formatPrice(save)}</Typography>}
        </Stack>
        <Box sx={{ mt: 0.5 }} data-testid="recommendation-reasons">
          <Typography variant="caption" fontWeight={800} color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '.06em' }}>Why this is recommended</Typography>
          {item.reasons.slice(0, compact ? 2 : 4).map((r) => (
            <Stack key={r} direction="row" spacing={0.5} alignItems="flex-start" sx={{ mt: 0.25 }}>
              <CheckCircleOutline sx={{ fontSize: 15, mt: '2px' }} color="success" />
              <Typography variant="caption" color="text.secondary">{r}</Typography>
            </Stack>
          ))}
        </Box>
        <Stack direction="row" spacing={1} sx={{ mt: 'auto', pt: 1 }}>
          <Button size="small" variant="contained" disabled={item.in_stock === false} onClick={() => onAdd(item)} data-testid="recommendation-add">Add to Cart</Button>
          <Button size="small" color="secondary" startIcon={<AutoAwesome fontSize="small" />} onClick={() => askAboutProduct({ productId: item.product_id, name: item.name })}>Ask AI</Button>
        </Stack>
      </Box>
    </Paper>
  );
};

/** The recommendations themselves: header, why-chips, cards with reasons, and the bundle with its real saving and "Add all to cart". */
export const RecommendationBlock = ({ data, title, compact = false, limit = 4 }: { data: AiRecommendations; title?: string; compact?: boolean; limit?: number }) => {
  const addItem = useCartStore((s) => s.addItem);
  const noteAdded = useUiStore((s) => s.noteAdded);
  const [snack, setSnack] = useState<{ message: string; error?: boolean } | null>(null);

  const add = async (item: AiRecommendationItem) => {
    try {
      await addItem({ productId: item.product_id, name: item.name, price: item.price ?? 0, imageUrl: item.image_url ?? '' }, 1);
      noteAdded({ productId: item.product_id, name: item.name });
      setSnack({ message: `✓ ${item.name} added to cart` });
    } catch (err) {
      setSnack({ message: getApiErrorMessage(err, 'Could not add to cart'), error: true });
    }
  };
  const addAll = async () => {
    try {
      for (const item of data.items) if (item.in_stock !== false) await addItem({ productId: item.product_id, name: item.name, price: item.price ?? 0, imageUrl: item.image_url ?? '' }, 1);
      setSnack({ message: `✓ ${data.items.length} items added to cart` });
    } catch (err) {
      setSnack({ message: getApiErrorMessage(err, 'Could not add everything to cart'), error: true });
    }
  };

  const bundle = data.bundle;
  return (
    <Box data-testid="recommendations" aria-label={data.title}>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
        <AutoAwesome color="secondary" fontSize="small" />
        <Typography variant={compact ? 'subtitle1' : 'h5'} fontWeight={800}>{title ?? data.title}</Typography>
      </Stack>
      {data.because.length > 0 && (
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.75, mb: 1.5 }}>
          {data.because.map((b) => <Chip key={b} size="small" variant="outlined" label={b} />)}
        </Stack>
      )}
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: compact ? '1fr' : { xs: 'repeat(2, 1fr)', md: `repeat(${Math.min(Math.max(data.items.length, 1), limit)}, 1fr)` } }}>
        {data.items.map((i) => <RecommendationCard key={i.product_id} item={i} compact={compact} onAdd={add} />)}
      </Box>
      {bundle && (
        <Paper variant="outlined" sx={{ mt: 1.5, p: 1.5, display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap', bgcolor: colors.greenSoft, borderColor: 'success.light' }} data-testid="bundle-summary">
          <Typography variant="body2" sx={{ flexGrow: 1 }}>
            <strong>{bundle.products.length} items together: {formatPrice(bundle.total)}</strong>
            {bundle.budget ? <> · within your {formatPrice(bundle.budget)} budget</> : null}
            {bundle.savings ? <> · <Box component="span" sx={{ color: 'success.dark', fontWeight: 800 }}>You save {formatPrice(bundle.savings)} with this combination</Box> (vs list prices)</> : null}
          </Typography>
          {!compact && data.items.length > 1 && <Button size="small" variant="contained" onClick={addAll} data-testid="bundle-add-all">Add all to cart</Button>}
        </Paper>
      )}
      <Snackbar open={!!snack} autoHideDuration={3000} onClose={() => setSnack(null)} anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        {snack ? <Alert severity={snack.error ? 'error' : 'success'} variant="filled" onClose={() => setSnack(null)}>{snack.message}</Alert> : undefined}
      </Snackbar>
    </Box>
  );
};

/** "Complete your setup" / "Recommended for you": real products with real reasons. Hidden when the AI service has nothing (or is down). */
const RecommendationSection = ({ productIds = [], title, limit = 4, compact = false, fallback = null }: Props) => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const { data, isLoading } = useQuery({
    queryKey: ['ai-recommendations', productIds.join(','), isAuthenticated, limit],
    queryFn: () => aiApi.recommendations(productIds, limit),
    retry: false,
    staleTime: 60_000,
  });
  if (isLoading) return <ProductRowSkeleton count={compact ? 2 : limit} />;
  if (!data || data.items.length === 0) return <>{fallback}</>;
  return <RecommendationBlock data={data} title={title} compact={compact} limit={limit} />;
};

export default RecommendationSection;
