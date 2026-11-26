import { Button } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { isGroundOwner } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { EmptyState } from '@/components/common/States';
import VenueManager from './VenueManager';

/** The owner console: your venues, their courts and sports, closing periods and bookings. Restricted by role on the server; this screen only hides itself. */
const OwnerVenuesPage = () => {
  const navigate = useNavigate();
  const { user, isAuthenticated } = useAuthStore();
  if (!isAuthenticated || !isGroundOwner(user?.roles)) {
    return (
      <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Venue owners only" body="This area is for venue owners. Ask an administrator to give your account the ground-owner role, then sign in again."
        action={<Button variant="contained" onClick={() => navigate('/venues')}>Browse venues</Button>} />
    );
  }
  return <VenueManager mode="owner" />;
};

export default OwnerVenuesPage;
