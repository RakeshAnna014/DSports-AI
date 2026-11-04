import { useEffect } from 'react';
import { Box, Button, IconButton, Paper, Slide, Stack, Typography } from '@mui/material';
import { CheckCircle, Close } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '@/store/uiStore';
import RecommendationSection from '@/components/products/RecommendationSection';
import { colors, shadows } from '@/design/tokens';

const VISIBLE_MS = 14_000;

/**
 * After any Add to Cart: a small confirmation (never a forced navigation) with the next steps and "Complete your setup" picks that
 * come with the reasons they were chosen. It leaves by itself; the cart drawer is one tap away.
 */
const AddedToCartPanel = () => {
  const navigate = useNavigate();
  const lastAdded = useUiStore((s) => s.lastAdded);
  const noteAdded = useUiStore((s) => s.noteAdded);
  const setCartOpen = useUiStore((s) => s.setCartOpen);
  const cartOpen = useUiStore((s) => s.cartOpen);

  useEffect(() => {
    if (!lastAdded) return undefined;
    const timer = window.setTimeout(() => noteAdded(null), VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [lastAdded, noteAdded]);

  const open = !!lastAdded && !cartOpen;
  return (
    <Slide direction="up" in={open} mountOnEnter unmountOnExit>
      <Paper elevation={0} role="status" aria-live="polite" data-testid="added-to-cart"
        sx={{ position: 'fixed', zIndex: (t) => t.zIndex.snackbar, right: { xs: 8, sm: 20 }, left: { xs: 8, sm: 'auto' }, bottom: { xs: 70, md: 20 }, width: { sm: 400 }, maxHeight: '70vh', overflowY: 'auto',
          p: 1.5, borderRadius: 4, border: `1px solid ${colors.line}`, boxShadow: shadows.cardHover }}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <CheckCircle color="success" />
          <Typography variant="subtitle2" fontWeight={800} sx={{ flexGrow: 1 }} noWrap>✓ Added to cart: {lastAdded?.name}</Typography>
          <IconButton size="small" aria-label="dismiss" onClick={() => noteAdded(null)}><Close fontSize="small" /></IconButton>
        </Stack>
        <Stack direction="row" spacing={1} sx={{ my: 1 }}>
          <Button size="small" variant="contained" onClick={() => setCartOpen(true)} data-testid="added-view-cart">View cart</Button>
          <Button size="small" variant="outlined" onClick={() => { noteAdded(null); navigate('/checkout'); }}>Checkout</Button>
          <Button size="small" onClick={() => noteAdded(null)}>Keep shopping</Button>
        </Stack>
        {lastAdded && <Box sx={{ mt: 0.5 }}><RecommendationSection productIds={[lastAdded.productId]} limit={2} compact title="Complete your setup" /></Box>}
      </Paper>
    </Slide>
  );
};

export default AddedToCartPanel;
