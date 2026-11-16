import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Paper, Stack, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { adminApi, isAdmin } from '@/api/venues';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';
import type { GroundOwner } from '@/types/venues';

const stamp = (iso: string) => new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });

/**
 * Who is a ground owner: give the role to someone who has already registered, remove it, and see the audit trail. No database access needed. The server
 * enforces the ADMIN role and refuses to let you grant the role to yourself; a removed role also stays in the person's current sign-in until it expires.
 */
const AdminGroundOwnersPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, isAuthenticated } = useAuthStore();
  const admin = isAuthenticated && isAdmin(user?.roles);
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState<{ severity: 'success' | 'info' | 'warning' | 'error'; text: string } | null>(null);
  const [toRemove, setToRemove] = useState<GroundOwner | null>(null);

  const owners = useQuery({ queryKey: ['ground-owners'], queryFn: adminApi.owners, enabled: admin });
  const audit = useQuery({ queryKey: ['ground-owner-audit'], queryFn: adminApi.ownerAudit, enabled: admin });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['ground-owners'] }); void queryClient.invalidateQueries({ queryKey: ['ground-owner-audit'] }); };

  const grant = useMutation({
    mutationFn: () => adminApi.grantOwner(email.trim()),
    onSuccess: (result) => {
      setMessage(result.changed
        ? { severity: 'success', text: `${result.email} is now a ground owner. They need to sign in again for it to take effect.` }
        : { severity: 'info', text: `${result.email} already is a ground owner.` });
      setEmail('');
      refresh();
    },
    onError: (err) => {
      const code = getApiError(err)?.code;
      if (code === 'USER_NOT_REGISTERED') setMessage({ severity: 'warning', text: 'Ask them to register first. Nobody has registered with that email.' });
      else if (code === 'CANNOT_CHANGE_OWN_ROLE') setMessage({ severity: 'error', text: 'You cannot give the role to yourself.' });
      else setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The role could not be given.') });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => adminApi.removeOwner(id),
    onSuccess: () => {
      setMessage({ severity: 'success', text: 'The ground-owner role was removed. Their venues stop taking new bookings now; existing bookings stay valid. Reassign the venues under Venues.' });
      setToRemove(null);
      refresh();
    },
    onError: (err) => { setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The role could not be removed.') }); setToRemove(null); },
  });

  if (!admin) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Administrators only" body="This area is for administrators." action={<Button variant="contained" onClick={() => navigate('/')}>Home</Button>} />;
  }
  return (
    <Box>
      <Typography variant="h3" component="h1">Ground owners</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        A ground owner can create venues, courts and closing periods, and see the bookings at their own venues. Give the role to someone who has already registered.
      </Typography>

      <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}
        onSubmit={(e) => { e.preventDefault(); if (email.trim()) grant.mutate(); }}>
        <TextField size="small" label="Email of a registered user" type="email" value={email} onChange={(e) => setEmail(e.target.value)} sx={{ minWidth: 300 }} inputProps={{ maxLength: 254 }} />
        <Button type="submit" variant="contained" disabled={!email.trim() || grant.isPending} data-testid="grant-owner">Make ground owner</Button>
      </Stack>
      {message && <Alert severity={message.severity} sx={{ mb: 2 }} onClose={() => setMessage(null)} data-testid="owners-message">{message.text}</Alert>}

      {owners.isError && <ErrorState body="We couldn't load the owners." onRetry={() => void owners.refetch()} />}
      {owners.data?.length === 0 && <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No ground owners yet" body="Enter the email of a registered user above." />}
      <Stack spacing={1} sx={{ mb: 3 }}>
        {owners.data?.map((o) => (
          <Paper key={o.id} variant="outlined" sx={{ p: 1.5 }} data-testid="owner-row">
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Box>
                <Typography variant="body1" fontWeight={700}>{o.email}</Typography>
                <Typography variant="caption" color="text.secondary">{o.firstName} {o.lastName}{o.grantedAt ? ` · made owner ${stamp(o.grantedAt)}` : ''}</Typography>
              </Box>
              <Button size="small" color="error" onClick={() => setToRemove(o)} data-testid="remove-owner">Remove role</Button>
            </Stack>
          </Paper>
        ))}
      </Stack>

      <Typography variant="h6" sx={{ mb: 1 }}>Audit log</Typography>
      {audit.data?.length === 0 && <Typography variant="body2" color="text.secondary">Nothing recorded yet.</Typography>}
      <Stack spacing={0.5} data-testid="owner-audit">
        {audit.data?.map((a) => (
          <Typography key={a.id} variant="body2" color="text.secondary">
            {stamp(a.occurredAt)} · {a.action === 'GRANTED' ? 'Role given to' : 'Role removed from'} <b>{a.targetEmail}</b> by {a.actorId === user?.userId ? 'you' : a.actorId.slice(0, 8)}
          </Typography>
        ))}
      </Stack>

      <Dialog open={!!toRemove} onClose={() => setToRemove(null)}>
        <DialogTitle>Remove the ground-owner role?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            {toRemove?.email} will no longer be a ground owner. Their venues stop taking new bookings and disappear from search right away; existing bookings stay valid.
            You can reassign the venues to another owner. Their current sign-in keeps the role for up to 15 minutes.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setToRemove(null)}>Keep role</Button>
          <Button color="error" variant="contained" disabled={remove.isPending} onClick={() => toRemove && remove.mutate(toRemove.id)} data-testid="confirm-remove-owner">Remove role</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default AdminGroundOwnersPage;
