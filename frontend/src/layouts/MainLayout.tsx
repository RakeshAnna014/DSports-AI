import { useEffect } from 'react';
import { Box, Container } from '@mui/material';
import { Outlet, useLocation } from 'react-router-dom';
import Header from '@/components/layout/Header';
import Footer from '@/components/layout/Footer';
import MobileNavigation from '@/components/layout/MobileNavigation';
import CartDrawer from '@/components/cart/CartDrawer';
import AddedToCartPanel from '@/components/cart/AddedToCartPanel';
import AssistantLauncher from '@/features/ai/AssistantLauncher';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';
import { useWishlistStore } from '@/store/wishlistStore';

/** Full-bleed pages (the dark hero) draw their own width; every other page sits in a standard container. */
const FULL_BLEED = new Set(['/']);

const MainLayout = () => {
  const { pathname } = useLocation();
  const { isAuthenticated, hydrate } = useAuthStore();
  const refreshCart = useCartStore((s) => s.refreshCart);
  const refreshWishlist = useWishlistStore((s) => s.refreshWishlist);

  useEffect(() => {
    hydrate();
  }, [hydrate]);

  useEffect(() => {
    refreshCart();                       // guests too: the cart store restores a guest's local cart
    if (isAuthenticated) refreshWishlist();
  }, [isAuthenticated, refreshCart, refreshWishlist]);

  const bleed = FULL_BLEED.has(pathname);
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Header />
      {bleed ? (
        <Box component="main" sx={{ flexGrow: 1 }}><Outlet /></Box>
      ) : (
        <Container component="main" maxWidth="lg" sx={{ flexGrow: 1, py: { xs: 2, md: 3.5 } }}><Outlet /></Container>
      )}
      <CartDrawer />
      <AddedToCartPanel />
      <AssistantLauncher />
      <Footer />
      <MobileNavigation />
    </Box>
  );
};

export default MainLayout;
