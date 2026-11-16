import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, FormControlLabel, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { adminApi, isAdmin } from '@/api/venues';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';

const slugOf = (name: string) => name.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

/**
 * The catalogue's sports and which of them can be booked as courts. Adding a sport here adds it to the catalogue; a bookable sport that has no products is
 * not shown in the shop, so it never appears there as an empty sport. The server enforces the ADMIN role.
 */
const AdminSportsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, isAuthenticated } = useAuthStore();
  const admin = isAuthenticated && isAdmin(user?.roles);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [bookable, setBookable] = useState(true);
  const [message, setMessage] = useState<{ severity: 'success' | 'error'; text: string } | null>(null);

  const sports = useQuery({ queryKey: ['admin-sports'], queryFn: adminApi.sports, enabled: admin });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['admin-sports'] }); void queryClient.invalidateQueries({ queryKey: ['bookable-sports'] }); };

  const add = useMutation({
    mutationFn: () => adminApi.addSport({ name: name.trim(), slug: slugOf(name), description: description.trim() || undefined, bookable }),
    onSuccess: (sport) => { setMessage({ severity: 'success', text: `${sport.name} was added${sport.bookable ? ' and can now be booked' : ''}.` }); setName(''); setDescription(''); refresh(); },
    onError: (err) => setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The sport could not be added.') }),
  });
  const toggle = useMutation({
    mutationFn: ({ id, value }: { id: string; value: boolean }) => adminApi.setSportBookable(sports.data!.find((s) => s.id === id)!, value),
    onSuccess: (sport) => { setMessage({ severity: 'success', text: `${sport.name} ${sport.bookable ? 'can now be booked' : 'can no longer be booked'}.` }); refresh(); },
    onError: (err) => setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The sport could not be changed.') }),
  });

  if (!admin) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Administrators only" body="This area is for administrators." action={<Button variant="contained" onClick={() => navigate('/')}>Home</Button>} />;
  }
  return (
    <Box>
      <Typography variant="h3" component="h1">Sports</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Venue owners can offer a sport on a court only when it is marked bookable. Existing bookings are not affected when you turn a sport off.
      </Typography>

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>Add a sport</Typography>
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}
          onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(); }}>
          <TextField size="small" label="Sport name" value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 100 }} />
          <TextField size="small" label="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} sx={{ minWidth: 240 }} />
          <FormControlLabel control={<Switch checked={bookable} onChange={(e) => setBookable(e.target.checked)} />} label="Bookable" />
          <Button type="submit" variant="contained" disabled={!name.trim() || add.isPending} data-testid="add-sport">Add sport</Button>
        </Stack>
      </Paper>
      {message && <Alert severity={message.severity} sx={{ mb: 2 }} onClose={() => setMessage(null)} data-testid="sports-message">{message.text}</Alert>}

      {sports.isError && <ErrorState body="We couldn't load the sports." onRetry={() => void sports.refetch()} />}
      <Stack spacing={1}>
        {sports.data?.map((s) => (
          <Paper key={s.id} variant="outlined" sx={{ p: 1.5 }} data-testid="sport-row">
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Box>
                <Typography variant="body1" fontWeight={700}>{s.name} {s.status === 'ARCHIVED' && <Chip size="small" label="Archived" />}</Typography>
                <Typography variant="caption" color="text.secondary">{s.slug}</Typography>
              </Box>
              <FormControlLabel label="Bookable" control={<Switch checked={s.bookable} disabled={s.status === 'ARCHIVED' || toggle.isPending}
                onChange={(e) => toggle.mutate({ id: s.id, value: e.target.checked })} inputProps={{ 'aria-label': `Bookable: ${s.name}` }} />} />
            </Stack>
          </Paper>
        ))}
      </Stack>
    </Box>
  );
};

export default AdminSportsPage;
