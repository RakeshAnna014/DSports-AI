import { useState } from 'react';
import { useNavigate, Link as RouterLink } from 'react-router-dom';
import { AxiosError } from 'axios';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Typography,
  Button,
  Alert,
  Link,
} from '@mui/material';
import { useAuthStore } from '@/store/authStore';

const LoginPage = () => {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login({ email, password });
      navigate('/', { replace: true });
    } catch (err: unknown) {
      if (err instanceof AxiosError && err.response?.data?.message) {
        setError(err.response.data.message);
      } else if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Invalid email or password');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh' }}>
      <Card sx={{ width: 420 }}>
        <CardContent sx={{ p: 4 }}>
          <Typography variant="h4" gutterBottom align="center">
            Sign In
          </Typography>

          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

          <Box component="form" onSubmit={handleSubmit}>
            <TextField
              fullWidth
              label="Email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              margin="normal"
              autoComplete="email"
            />
            <TextField
              fullWidth
              label="Password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              margin="normal"
              autoComplete="current-password"
            />
            <Button
              type="submit"
              fullWidth
              variant="contained"
              size="large"
              disabled={loading}
              sx={{ mt: 2 }}
            >
              {loading ? 'Signing in...' : 'Sign In'}
            </Button>
          </Box>

          <Typography variant="body2" align="center" sx={{ mt: 2 }}>
            Don&apos;t have an account?{' '}
            <Link component={RouterLink} to="/register" underline="hover">
              Create one
            </Link>
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
};

export default LoginPage;
