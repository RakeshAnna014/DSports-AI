import { useEffect, useState } from 'react';
import { AxiosError } from 'axios';
import {
  Box,
  Typography,
  Card,
  CardContent,
  Avatar,
  CircularProgress,
  Alert,
  Chip,
  Button,
  TextField,
} from '@mui/material';
import { PersonOutline } from '@mui/icons-material';
import { profileApi } from '@/api/profile';
import type { CustomerProfile } from '@/types/profile';

const ProfilePage = () => {
  const [profile, setProfile] = useState<CustomerProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({ firstName: '', lastName: '', phoneNumber: '' });

  useEffect(() => {
    profileApi
      .getProfile()
      .then((data) => {
        setProfile(data);
        setForm({ firstName: data.firstName, lastName: data.lastName, phoneNumber: data.phoneNumber ?? '' });
      })
      .catch((err) => {
        setError(err instanceof AxiosError && err.response?.data?.message ? err.response.data.message : 'Failed to load profile');
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    if (!profile) return;
    setSaving(true);
    setError('');
    try {
      const updated = await profileApi.updateProfile({
        firstName: form.firstName,
        lastName: form.lastName,
        phoneNumber: form.phoneNumber || undefined,
      });
      setProfile(updated);
      setEditing(false);
    } catch (err) {
      setError(err instanceof AxiosError && err.response?.data?.message ? err.response.data.message : 'Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    if (!profile) return;
    setForm({ firstName: profile.firstName, lastName: profile.lastName, phoneNumber: profile.phoneNumber ?? '' });
    setEditing(false);
    setError('');
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!profile && !error) {
    return <Alert severity="warning">Profile not found.</Alert>;
  }

  return (
    <Box sx={{ maxWidth: 600, mx: 'auto' }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h4">My Profile</Typography>
        {!editing && (
          <Button variant="outlined" onClick={() => setEditing(true)}>Edit</Button>
        )}
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {profile && !editing && (
        <Card>
          <CardContent sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, py: 4 }}>
            <Avatar sx={{ width: 80, height: 80, bgcolor: 'primary.main' }}>
              <PersonOutline sx={{ fontSize: 40 }} />
            </Avatar>
            <Box sx={{ textAlign: 'center' }}>
              <Typography variant="h5">{profile.firstName} {profile.lastName}</Typography>
              <Typography variant="body1" color="text.secondary">{profile.email}</Typography>
            </Box>
            <Box sx={{ display: 'flex', gap: 1 }}>
              {profile.roles.map((role) => (
                <Chip key={role} label={role} color="primary" variant="outlined" size="small" />
              ))}
            </Box>
            {profile.phoneNumber && (
              <Typography variant="body2" color="text.secondary">Phone: {profile.phoneNumber}</Typography>
            )}
          </CardContent>
        </Card>
      )}

      {profile && editing && (
        <Card>
          <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, py: 4 }}>
            <Typography variant="h6">Edit Profile</Typography>
            <TextField
              label="First Name"
              value={form.firstName}
              onChange={(e) => setForm({ ...form, firstName: e.target.value })}
              required
              fullWidth
            />
            <TextField
              label="Last Name"
              value={form.lastName}
              onChange={(e) => setForm({ ...form, lastName: e.target.value })}
              required
              fullWidth
            />
            <TextField
              label="Phone Number"
              value={form.phoneNumber}
              onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })}
              fullWidth
            />
            <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
              <Button variant="outlined" onClick={handleCancel} disabled={saving}>Cancel</Button>
              <Button variant="contained" onClick={handleSave} disabled={saving}>
                {saving ? 'Saving...' : 'Save'}
              </Button>
            </Box>
          </CardContent>
        </Card>
      )}
    </Box>
  );
};

export default ProfilePage;
