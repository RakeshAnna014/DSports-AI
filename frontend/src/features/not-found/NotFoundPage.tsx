import { Box, Typography, Button } from '@mui/material';
import { useNavigate } from 'react-router-dom';

const NotFoundPage = () => {
  const navigate = useNavigate();
  return (
    <Box sx={{ textAlign: 'center', py: 8 }}>
      <Typography variant="h3" gutterBottom>404</Typography>
      <Typography variant="h5" color="text.secondary" sx={{ mb: 4 }}>
        Page not found
      </Typography>
      <Button variant="contained" onClick={() => navigate('/')}>Go Home</Button>
    </Box>
  );
};

export default NotFoundPage;
