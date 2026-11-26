import { Button } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { isAdmin } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { EmptyState } from '@/components/common/States';
import VenueManager from '@/features/venues/VenueManager';

/** Administrators create and edit venues on an owner's behalf and reassign a venue whose owner lost the role. The server enforces the ADMIN role. */
const AdminVenuesPage = () => {
  const navigate = useNavigate();
  const { user, isAuthenticated } = useAuthStore();
  if (!isAuthenticated || !isAdmin(user?.roles)) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Administrators only" body="This area is for administrators." action={<Button variant="contained" onClick={() => navigate('/')}>Home</Button>} />;
  }
  return <VenueManager mode="admin" />;
};

export default AdminVenuesPage;
