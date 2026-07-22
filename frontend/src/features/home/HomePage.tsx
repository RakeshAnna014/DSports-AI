import { Box, Typography, Button, Container } from '@mui/material';
import { useNavigate } from 'react-router-dom';

const HomePage = () => {
  const navigate = useNavigate();

  return (
    <Container maxWidth="md">
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          textAlign: 'center',
          py: 8,
        }}
      >
        <Typography variant="h1" color="primary.main" gutterBottom>
          DSports-AI
        </Typography>
        <Typography variant="h5" color="text.secondary" sx={{ mb: 4 }}>
          AI-powered sports commerce platform
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 4, maxWidth: 600 }}>
          Premium cricket equipment and apparel for players, teams, and franchises.
        </Typography>
        <Box sx={{ display: 'flex', gap: 2 }}>
          <Button variant="contained" size="large" onClick={() => navigate('/products')}>
            Shop Now
          </Button>
          <Button variant="outlined" size="large" onClick={() => navigate('/products')}>
            Learn More
          </Button>
        </Box>
      </Box>
    </Container>
  );
};

export default HomePage;
