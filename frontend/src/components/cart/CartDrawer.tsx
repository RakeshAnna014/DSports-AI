import { useMemo } from 'react';
import { Box, Button, Divider, Drawer, IconButton, Stack, Typography } from '@mui/material';
import { Add, AutoAwesome, Close, DeleteOutline, Remove, ShoppingCartOutlined } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useCartStore } from '@/store/cartStore';
import { useUiStore } from '@/store/uiStore';
import { useAiStore } from '@/store/aiStore';
import { usePrices } from '@/hooks/useCatalog';
import { formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { EmptyState } from '@/components/common/States';
import RecommendationSection from '@/components/products/RecommendationSection';
import { colors } from '@/design/tokens';

/** The cart without leaving the page: items, real savings (list price vs selling price), "complete your setup" and checkout. */
const CartDrawer = () => {
  const navigate = useNavigate();
  const open = useUiStore((s) => s.cartOpen);
  const setOpen = useUiStore((s) => s.setCartOpen);
  const { items, updateQuantity, removeItem } = useCartStore();
  const setAiOpen = useAiStore((s) => s.setOpen);
  const { data: prices = [] } = usePrices();
  const mrpById = useMemo(() => new Map(prices.map((p) => [p.productId, p.mrp])), [prices]);
  const subtotal = items.reduce((sum, i) => sum + (i.lineTotal ?? i.price * i.quantity), 0);
  const savings = items.reduce((sum, i) => sum + Math.max(0, (mrpById.get(i.productId) ?? i.price) - i.price) * i.quantity, 0);
  const go = (path: string) => { setOpen(false); navigate(path); };

  return (
    <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: { xs: '100%', sm: 440 }, bgcolor: colors.surface } }} data-testid="cart-drawer">
      <Stack direction="row" alignItems="center" sx={{ px: 2, py: 1.5, bgcolor: colors.navy800, color: '#fff' }}>
        <ShoppingCartOutlined sx={{ mr: 1 }} />
        <Typography variant="subtitle1" fontWeight={800} sx={{ flexGrow: 1, letterSpacing: '.04em' }}>YOUR CART</Typography>
        <IconButton color="inherit" size="small" aria-label="close cart" onClick={() => setOpen(false)}><Close /></IconButton>
      </Stack>
      <Box sx={{ flexGrow: 1, overflowY: 'auto', p: 2 }}>
        {items.length === 0 ? (
          <EmptyState title="Your cart is empty" body="Find something you will love, or ask DSports AI to recommend the right gear."
            action={<Stack direction="row" spacing={1}>
              <Button variant="contained" onClick={() => go('/products')}>Browse products</Button>
              <Button color="secondary" startIcon={<AutoAwesome />} onClick={() => { setOpen(false); setAiOpen(true); }}>Ask DSports AI</Button>
            </Stack>} />
        ) : (
          <Stack spacing={1.25} data-testid="cart-items">
            {items.map((i) => (
              <Stack key={i.productId} direction="row" spacing={1.5} sx={{ bgcolor: 'background.paper', p: 1.25, borderRadius: 3, border: `1px solid ${colors.line}` }}>
                <ProductImage productId={i.productId} alt={i.name} sx={{ width: 68, flexShrink: 0, borderRadius: 2 }} />
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Typography variant="subtitle2" fontWeight={700} noWrap title={i.name}>{i.name}</Typography>
                  <Typography variant="body2" color="primary.dark" fontWeight={800}>{formatPrice(i.price)}</Typography>
                  <Stack direction="row" alignItems="center" spacing={0.5}>
                    <IconButton size="small" aria-label={`decrease ${i.name}`} disabled={i.quantity <= 1} onClick={() => updateQuantity(i.productId, i.quantity - 1)}><Remove fontSize="small" /></IconButton>
                    <Typography variant="body2" sx={{ minWidth: 20, textAlign: 'center' }}>{i.quantity}</Typography>
                    <IconButton size="small" aria-label={`increase ${i.name}`} onClick={() => updateQuantity(i.productId, i.quantity + 1)}><Add fontSize="small" /></IconButton>
                    <Box sx={{ flexGrow: 1 }} />
                    <IconButton size="small" aria-label={`remove ${i.name}`} onClick={() => removeItem(i.productId)}><DeleteOutline fontSize="small" /></IconButton>
                  </Stack>
                </Box>
              </Stack>
            ))}
            <Box sx={{ pt: 1 }}>
              <RecommendationSection productIds={items.map((i) => i.productId)} limit={3} compact title="Complete your setup" />
            </Box>
          </Stack>
        )}
      </Box>
      {items.length > 0 && (
        <Box sx={{ p: 2, bgcolor: 'background.paper', borderTop: `1px solid ${colors.line}` }}>
          <Stack spacing={0.5} sx={{ mb: 1.5 }}>
            <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">Subtotal</Typography><Typography variant="body2" fontWeight={700} data-testid="cart-subtotal">{formatPrice(subtotal)}</Typography></Stack>
            {savings > 0 && <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="success.main">You save (vs list price)</Typography><Typography variant="body2" color="success.main" fontWeight={800} data-testid="cart-savings">{formatPrice(savings)}</Typography></Stack>}
          </Stack>
          <Divider sx={{ mb: 1.5 }} />
          <Stack direction="row" spacing={1}>
            <Button fullWidth variant="outlined" onClick={() => setOpen(false)}>Continue shopping</Button>
            <Button fullWidth variant="contained" onClick={() => go('/checkout')} data-testid="cart-checkout">Checkout</Button>
          </Stack>
          <Button fullWidth size="small" sx={{ mt: 0.5 }} onClick={() => go('/cart')}>View full cart</Button>
        </Box>
      )}
    </Drawer>
  );
};

export default CartDrawer;
