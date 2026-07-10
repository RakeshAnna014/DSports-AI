import {
  AppBar,
  Toolbar,
  Typography,
  Container,
  Box,
  Button,
  IconButton,
  Badge,
} from '@mui/material';
import ShoppingCartOutlined from '@mui/icons-material/ShoppingCartOutlined';
import { Outlet, Link as RouterLink } from 'react-router-dom';

const MainLayout = () => {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <AppBar position="sticky" color="inherit">
        <Toolbar>
          <Typography
            variant="h6"
            component={RouterLink}
            to="/"
            sx={{
              fontWeight: 700,
              color: 'primary.main',
              textDecoration: 'none',
              mr: 4,
            }}
          >
            DSports-AI
          </Typography>

          <Box sx={{ display: 'flex', gap: 1, flexGrow: 1 }}>
            <Button color="inherit" component={RouterLink} to="/">
              Home
            </Button>
            <Button color="inherit" component={RouterLink} to="/products">
              Products
            </Button>
          </Box>

          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
            <IconButton color="inherit" aria-label="cart">
              <Badge badgeContent={0} color="secondary">
                <ShoppingCartOutlined />
              </Badge>
            </IconButton>
            <Button variant="contained" color="primary" size="small">
              Sign In
            </Button>
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
