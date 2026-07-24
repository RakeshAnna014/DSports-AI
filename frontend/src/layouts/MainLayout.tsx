import { useEffect, useState } from 'react';
import {
  AppBar,
  Toolbar,
  Typography,
  Container,
  Box,
  Button,
  IconButton,
  Badge,
  Avatar,
  Menu,
  MenuItem,
  Divider,
  ListItemIcon,
} from '@mui/material';
import {
  ShoppingCartOutlined,
  PersonOutline,
  ReceiptLongOutlined,
  Logout,
} from '@mui/icons-material';
import {
  Link as MuiLink,
} from '@mui/material';
import { Outlet, Link as RouterLink, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';

const MainLayout = () => {
  const navigate = useNavigate();
  const { user, isAuthenticated, logout, hydrate } = useAuthStore();
  const totalCartItems = useCartStore((s) => s.totalItems);
  const refreshCart = useCartStore((s) => s.refreshCart);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);

  useEffect(() => {
    hydrate();
  }, [hydrate]);

  useEffect(() => {
    if (isAuthenticated) {
      refreshCart();
    }
  }, [isAuthenticated, refreshCart]);

  const handleLogout = async () => {
    setAnchorEl(null);
    await logout();
    navigate('/');
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <AppBar position="sticky" color="inherit">
        <Toolbar>
          <MuiLink
            component={RouterLink}
            to="/"
            variant="h6"
            sx={{
              fontWeight: 700,
              color: 'primary.main',
              textDecoration: 'none',
              mr: 4,
              cursor: 'pointer',
            }}
          >
            DSports-AI
          </MuiLink>

          <Box sx={{ display: 'flex', gap: 1, flexGrow: 1 }}>
            <Button color="inherit" component={RouterLink} to="/">
              Home
            </Button>
            <Button color="inherit" component={RouterLink} to="/products">
              Products
            </Button>
          </Box>

          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
            <IconButton color="inherit" aria-label="cart" onClick={() => navigate('/cart')}>
              <Badge badgeContent={totalCartItems} color="secondary">
                <ShoppingCartOutlined />
              </Badge>
            </IconButton>

            {isAuthenticated && user ? (
              <>
                <IconButton
                  onClick={(e) => setAnchorEl(e.currentTarget)}
                  size="small"
                  sx={{ ml: 1 }}
                >
                  <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main' }}>
                    <PersonOutline />
                  </Avatar>
                </IconButton>
                <Menu
                  anchorEl={anchorEl}
                  open={Boolean(anchorEl)}
                  onClose={() => setAnchorEl(null)}
                  transformOrigin={{ horizontal: 'right', vertical: 'top' }}
                  anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
                >
                  <MenuItem disabled>
                    <Typography variant="body2" color="text.secondary">
                      {user.email}
                    </Typography>
                  </MenuItem>
                  <Divider />
                  <MenuItem onClick={() => { setAnchorEl(null); navigate('/orders'); }}>
                    <ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>
                    My Orders
                  </MenuItem>
                  <MenuItem onClick={() => { setAnchorEl(null); navigate('/payments'); }}>
                    <ListItemIcon><ReceiptLongOutlined fontSize="small" /></ListItemIcon>
                    Payments
                  </MenuItem>
                  <MenuItem onClick={() => { setAnchorEl(null); navigate('/profile'); }}>
                    <ListItemIcon><PersonOutline fontSize="small" /></ListItemIcon>
                    Profile
                  </MenuItem>
                  <MenuItem onClick={handleLogout}>
                    <ListItemIcon><Logout fontSize="small" /></ListItemIcon>
                    Sign Out
                  </MenuItem>
                </Menu>
              </>
            ) : (
              <Button
                variant="contained"
                color="primary"
                size="small"
                component={RouterLink}
                to="/login"
              >
                Sign In
              </Button>
            )}
          </Box>
        </Toolbar>
      </AppBar>

      <Container component="main" maxWidth="lg" sx={{ flexGrow: 1, py: 3 }}>
        <Outlet />
      </Container>

      <Box
        component="footer"
        sx={{ py: 2, textAlign: 'center', bgcolor: 'grey.100' }}
      >
        <Typography variant="body2" color="text.secondary">
          &copy; {new Date().getFullYear()} DSports-AI. All rights reserved.
        </Typography>
      </Box>
    </Box>
  );
};

export default MainLayout;
