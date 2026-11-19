import { useState } from 'react';
import { AppBar, Avatar, Badge, Box, Button, Divider, IconButton, ListItemIcon, Menu, MenuItem, Toolbar, Tooltip, Typography } from '@mui/material';
import { AutoAwesome, FavoriteBorder, LocalShipping, Logout, PersonOutline, ReceiptLongOutlined, SearchRounded, ShoppingCartOutlined } from '@mui/icons-material';
import { NavLink, Link as RouterLink, useNavigate } from 'react-router-dom';
import Brand from '@/components/common/Brand';
import NotificationCenter from '@/features/notifications/NotificationCenter';
import { isBuyer } from '@/api/sourcing';
import { isPlanner } from '@/api/planning';
import { isAdmin, isGroundOwner } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';
import { useWishlistStore } from '@/store/wishlistStore';
import { useAiStore } from '@/store/aiStore';
import { useUiStore } from '@/store/uiStore';
import { colors, gradients } from '@/design/tokens';

const NAV = [
  { label: 'Home', to: '/', end: true },
  { label: 'Products', to: '/products' },
  { label: 'Courts', to: '/venues' },
  { label: 'Live cricket', to: '/live' },
  { label: 'AI Shop', to: '/ai' },
  { label: 'Orders', to: '/orders' },
  { label: 'Track', to: '/track' },
  { label: 'Returns', to: '/returns' },
  { label: 'Support', to: '/support' },
];

const iconSx = { color: 'rgba(255,255,255,0.86)', '&:hover': { color: '#fff', bgcolor: 'rgba(255,255,255,0.08)' } };

const Header = () => {
  const navigate = useNavigate();
  const { user, isAuthenticated, logout } = useAuthStore();
  const totalCartItems = useCartStore((s) => s.totalItems);
  const totalWishlistItems = useWishlistStore((s) => s.totalItems);
  const setAiOpen = useAiStore((s) => s.setOpen);
  const setCartOpen = useUiStore((s) => s.setCartOpen);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const [adminAnchor, setAdminAnchor] = useState<null | HTMLElement>(null);

  const handleLogout = async () => {
    setAnchorEl(null);
    await logout();
    navigate('/');
  };
  const goto = (path: string) => { setAnchorEl(null); setAdminAnchor(null); navigate(path); };

  return (
    <AppBar position="sticky" elevation={0} sx={{ bgcolor: 'rgba(7,12,24,0.86)', backdropFilter: 'blur(14px)', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
      <Toolbar sx={{ gap: { xs: 1, md: 1, lg: 2 }, minHeight: { xs: 60, md: 68 } }}>
        <Brand tone="light" />

        <Box component="nav" aria-label="Main" data-testid="main-nav" sx={{ display: { xs: 'none', md: 'flex' }, gap: { md: 0, lg: 0.5 }, ml: { md: 0.5, lg: 2 }, flexGrow: 1, flexShrink: 1, minWidth: 0, overflowX: 'auto',
            scrollbarWidth: 'none', '&::-webkit-scrollbar': { display: 'none' }, '& > .MuiButton-root': { flexShrink: 0, whiteSpace: 'nowrap' } }}>
          {NAV.map((n) => (
            <Button key={n.to} component={NavLink} to={n.to} end={n.end}
              sx={{ color: 'rgba(255,255,255,0.78)', fontWeight: 600, borderRadius: 2, px: { md: 0.75, lg: 1, xl: 1.5 }, minWidth: 0, whiteSpace: 'nowrap', fontSize: { md: '0.8rem', lg: '0.8125rem', xl: '0.875rem' }, position: 'relative',
                '&:hover': { color: '#fff', bgcolor: 'rgba(255,255,255,0.06)' },
                '&.active': { color: '#fff', '&::after': { content: '""', position: 'absolute', left: 12, right: 12, bottom: 4, height: 2, borderRadius: 2, background: gradients.brand } } }}>
              {n.label}
            </Button>
          ))}
          {isGroundOwner(user?.roles) && (
            <Button component={NavLink} to="/owner/venues" data-testid="nav-owner-console" sx={{ color: '#B8F23C', fontWeight: 700, borderRadius: 2, px: 1.5, whiteSpace: 'nowrap' }}>Owner console</Button>
          )}
          {isAdmin(user?.roles) && (
            <>
              <Button onClick={(e) => setAdminAnchor(e.currentTarget)} data-testid="nav-admin" aria-haspopup="menu" aria-expanded={Boolean(adminAnchor)}
                sx={{ color: '#B8F23C', fontWeight: 700, borderRadius: 2, px: 1.5, whiteSpace: 'nowrap' }}>Admin</Button>
              <Menu anchorEl={adminAnchor} open={Boolean(adminAnchor)} onClose={() => setAdminAnchor(null)} MenuListProps={{ 'aria-label': 'Admin' }}>
                <MenuItem onClick={() => goto('/admin/ground-owners')} data-testid="admin-ground-owners">Ground owners</MenuItem>
                <MenuItem onClick={() => goto('/admin/venues')} data-testid="admin-venues">Venues</MenuItem>
                <MenuItem onClick={() => goto('/admin/sports')} data-testid="admin-sports">Sports</MenuItem>
                <Divider />
                <MenuItem onClick={() => goto('/inventory-ai')} data-testid="nav-inventory-ai">Inventory AI</MenuItem>
                <MenuItem onClick={() => goto('/engineering-ai')} data-testid="nav-engineering-ai">Engineering AI</MenuItem>
                <MenuItem onClick={() => goto('/fulfilment')} data-testid="nav-fulfilment">Fulfilment</MenuItem>
                <MenuItem onClick={() => goto('/support-cases')} data-testid="nav-support-console">Support console</MenuItem>
              </Menu>
            </>
          )}
          {isBuyer(user?.roles) && (
            <Button component={NavLink} to="/product-intelligence" data-testid="nav-product-intelligence" sx={{ color: '#B8F23C', fontWeight: 700, borderRadius: 2, px: 1.5 }}>Product Intelligence</Button>
          )}
          {isPlanner(user?.roles) && (
            <Button component={NavLink} to="/sales-planning" data-testid="nav-sales-planning" sx={{ color: '#B8F23C', fontWeight: 700, borderRadius: 2, px: 1.5 }}>Sales Planning</Button>
          )}
          {!isAdmin(user?.roles) && user?.roles?.some((r) => r === 'SUPPORT_EXECUTIVE' || r === 'ROLE_SUPPORT_EXECUTIVE') && (
            <Button component={NavLink} to="/support-cases" data-testid="nav-support-console" sx={{ color: '#B8F23C', fontWeight: 700, borderRadius: 2, px: 1.5 }}>Support console</Button>
          )}
        </Box>
        <Box sx={{ flexGrow: { xs: 1, md: 0 } }} />

        <Button variant="contained" color="secondary" startIcon={<AutoAwesome />} onClick={() => setAiOpen(true)} data-testid="ai-header-button"
          aria-label="Ask DSports AI" sx={{ flexShrink: 0, background: gradients.ai, color: '#1b1200', fontWeight: 800, borderRadius: 999, px: { xs: 1.5, md: 2.25 }, boxShadow: '0 6px 22px rgba(255,122,26,0.38)',
            '& .MuiButton-startIcon': { mr: { xs: 0, sm: 1 } } }}>
          <Box component="span" sx={{ display: { xs: 'none', sm: 'inline', md: 'none', xl: 'inline' }, whiteSpace: 'nowrap' }}>Ask DSports AI</Box>
        </Button>

        <Box sx={{ display: 'flex', alignItems: 'center' }}>
          <Tooltip title="Search with AI"><IconButton sx={{ ...iconSx, display: { xs: 'none', sm: 'inline-flex' } }} aria-label="search" onClick={() => navigate('/ai')}><SearchRounded /></IconButton></Tooltip>
          <Box sx={{ '& .MuiIconButton-root': iconSx }}><NotificationCenter /></Box>
          <IconButton sx={iconSx} aria-label="wishlist" onClick={() => navigate('/wishlist')}>
            <Badge badgeContent={totalWishlistItems} color="error"><FavoriteBorder /></Badge>
          </IconButton>
          <IconButton sx={iconSx} aria-label="cart" onClick={() => setCartOpen(true)} data-testid="cart-button">
            <Badge badgeContent={totalCartItems} color="secondary"><ShoppingCartOutlined /></Badge>
          </IconButton>
          {isAuthenticated && user ? (
            <>
              <IconButton onClick={(e) => setAnchorEl(e.currentTarget)} size="small" sx={{ ml: 0.5 }} aria-label="account menu">
                <Avatar sx={{ width: 34, height: 34, background: gradients.brand, color: colors.navy900 }}><PersonOutline /></Avatar>
              </IconButton>
              <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={() => setAnchorEl(null)} transformOrigin={{ horizontal: 'right', vertical: 'top' }}
                anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}>
                <MenuItem disabled><Typography variant="body2" color="text.secondary">{user.email}</Typography></MenuItem>
                <Divider />
                <MenuItem onClick={() => goto('/orders')}><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>My Orders</MenuItem>
                <MenuItem onClick={() => goto('/teams')} data-testid="menu-teams"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>My teams</MenuItem>
                <MenuItem onClick={() => goto('/matches')} data-testid="menu-matches"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>My matches</MenuItem>
                <MenuItem onClick={() => goto('/players')} data-testid="menu-players"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Players</MenuItem>
                <MenuItem onClick={() => goto('/bookings')} data-testid="menu-bookings"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>My bookings</MenuItem>
                {isGroundOwner(user.roles) && <MenuItem onClick={() => goto('/owner/venues')} data-testid="menu-owner-console"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Owner console</MenuItem>}
                <MenuItem onClick={() => goto('/returns')}><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Returns</MenuItem>
                <MenuItem onClick={() => goto('/support')} data-testid="menu-support"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Support cases</MenuItem>
                <MenuItem onClick={() => goto('/support?new=1')} data-testid="menu-raise-ticket"><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Contact support</MenuItem>
                <MenuItem onClick={() => goto('/notifications')}><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Notifications</MenuItem>
                <MenuItem onClick={() => goto('/payments')}><ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>Payments</MenuItem>
                <MenuItem onClick={() => goto('/track')}><ListItemIcon><LocalShipping fontSize="small" /></ListItemIcon>Track Shipment</MenuItem>
                <MenuItem onClick={() => goto('/profile')}><ListItemIcon><PersonOutline fontSize="small" /></ListItemIcon>Profile</MenuItem>
                <MenuItem onClick={handleLogout}><ListItemIcon><Logout fontSize="small" /></ListItemIcon>Sign Out</MenuItem>
              </Menu>
            </>
          ) : (
            <Button variant="contained" size="small" component={RouterLink} to="/login" sx={{ ml: 1, background: gradients.brand, color: colors.navy900, fontWeight: 800 }}>Sign In</Button>
          )}
        </Box>
      </Toolbar>
    </AppBar>
  );
};

export default Header;
