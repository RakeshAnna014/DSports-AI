import { Badge, BottomNavigation, BottomNavigationAction, Paper } from '@mui/material';
import { AutoAwesome, HomeOutlined, ReceiptLongOutlined, ShoppingCartOutlined, StorefrontOutlined } from '@mui/icons-material';
import { useLocation, useNavigate } from 'react-router-dom';
import { useCartStore } from '@/store/cartStore';
import { useAiStore } from '@/store/aiStore';
import { useUiStore } from '@/store/uiStore';
import { colors } from '@/design/tokens';

/** Phone navigation: Home, AI, Products, Orders, Cart. The AI is one tap away. */
const MobileNavigation = () => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const cartCount = useCartStore((s) => s.totalItems);
  const setCartOpen = useUiStore((s) => s.setCartOpen);
  const setAiOpen = useAiStore((s) => s.setOpen);
  const value = pathname === '/' ? 'home' : pathname.startsWith('/ai') ? 'ai' : pathname.startsWith('/products') ? 'products' : pathname.startsWith('/orders') ? 'orders' : false;

  return (
    <Paper component="nav" aria-label="Mobile" elevation={0}
      sx={{ display: { xs: 'block', md: 'none' }, position: 'fixed', left: 0, right: 0, bottom: 0, zIndex: (t) => t.zIndex.appBar, borderRadius: 0, borderTop: `1px solid ${colors.line}`, pb: 'env(safe-area-inset-bottom)' }}>
      <BottomNavigation showLabels value={value}>
        <BottomNavigationAction value="home" label="Home" icon={<HomeOutlined />} onClick={() => navigate('/')} />
        <BottomNavigationAction value="ai" label="AI" icon={<AutoAwesome color="secondary" />} onClick={() => { navigate('/ai'); setAiOpen(false); }} />
        <BottomNavigationAction value="products" label="Products" icon={<StorefrontOutlined />} onClick={() => navigate('/products')} />
        <BottomNavigationAction value="orders" label="Orders" icon={<ReceiptLongOutlined />} onClick={() => navigate('/orders')} />
        <BottomNavigationAction label="Cart" icon={<Badge badgeContent={cartCount} color="secondary"><ShoppingCartOutlined /></Badge>} onClick={() => setCartOpen(true)} />
      </BottomNavigation>
    </Paper>
  );
};

export default MobileNavigation;
