import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { groundsApi, isGroundOwner } from '@/api/grounds';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { localToday, refundSentence, when } from '@/lib/groundSlots';
import type { Booking, GroundInput, OwnerGround, PitchType } from '@/types/grounds';

const PITCHES: PitchType[] = ['TURF', 'MATTING', 'CONCRETE', 'NATURAL_GRASS', 'INDOOR'];
const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '');
const toNumber = (v: string) => (v.trim() === '' ? null : Number(v));

const BLANK: GroundInput = {
  name: '', address: '', city: '', latitude: null, longitude: null, pitchType: 'TURF', facilities: [], photoUrls: [], opensAt: '06:00', closesAt: '22:00',
  timeZone: 'Asia/Kolkata', slotMinutes: 60, basePrice: 1000, weekendPrice: null, peakPrice: null, peakFrom: null, peakTo: null,
};

const GroundForm = ({ initial, existing, onClose, onSaved }: { initial: GroundInput; existing?: OwnerGround; onClose: () => void; onSaved: () => void }) => {
  const [f, setF] = useState<GroundInput>(initial);
  const [active, setActive] = useState(existing?.ground.active ?? true);
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => (existing ? groundsApi.owner.update(existing.ground.id, f, active, existing.version) : groundsApi.owner.create(f)),
    onSuccess: onSaved,
    onError: (err) => setError(getApiErrorMessage(err, 'The ground could not be saved.')),
  });
  const set = <K extends keyof GroundInput>(key: K, value: GroundInput[K]) => setF((p) => ({ ...p, [key]: value }));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{existing ? 'Edit ground' : 'New ground'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <TextField size="small" label="Name" value={f.name} onChange={(e) => set('name', e.target.value)} required />
          <TextField size="small" label="Address" value={f.address} onChange={(e) => set('address', e.target.value)} required />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="City" value={f.city} onChange={(e) => set('city', e.target.value)} required fullWidth />
            <TextField size="small" select label="Pitch" value={f.pitchType} onChange={(e) => set('pitchType', e.target.value as PitchType)} fullWidth>
              {PITCHES.map((p) => <MenuItem key={p} value={p}>{p.replace('_', ' ').toLowerCase()}</MenuItem>)}
            </TextField>
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Opens" type="time" value={hhmm(f.opensAt)} onChange={(e) => set('opensAt', e.target.value)} InputLabelProps={{ shrink: true }} fullWidth />
            <TextField size="small" label="Closes" type="time" value={hhmm(f.closesAt)} onChange={(e) => set('closesAt', e.target.value)} InputLabelProps={{ shrink: true }} fullWidth />
            <TextField size="small" label="Slot (minutes)" type="number" value={f.slotMinutes} onChange={(e) => set('slotMinutes', Number(e.target.value))} fullWidth />
          </Stack>
          <TextField size="small" label="Time zone" value={f.timeZone} onChange={(e) => set('timeZone', e.target.value)} helperText="Opening hours are read in this zone, e.g. Asia/Kolkata" />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Base price (₹ per slot)" type="number" value={f.basePrice} onChange={(e) => set('basePrice', Number(e.target.value))} fullWidth />
            <TextField size="small" label="Weekend price" type="number" value={f.weekendPrice ?? ''} onChange={(e) => set('weekendPrice', toNumber(e.target.value))} fullWidth />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Peak price" type="number" value={f.peakPrice ?? ''} onChange={(e) => set('peakPrice', toNumber(e.target.value))} fullWidth />
            <TextField size="small" label="Peak from" type="time" value={hhmm(f.peakFrom)} onChange={(e) => set('peakFrom', e.target.value || null)} InputLabelProps={{ shrink: true }} fullWidth />
            <TextField size="small" label="Peak to" type="time" value={hhmm(f.peakTo)} onChange={(e) => set('peakTo', e.target.value || null)} InputLabelProps={{ shrink: true }} fullWidth />
          </Stack>
          <TextField size="small" label="Facilities (comma separated)" value={f.facilities.join(', ')}
            onChange={(e) => set('facilities', e.target.value.split(',').map((x) => x.trim()).filter(Boolean))} />
          {existing && <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Bookable" />}
          {error && <Alert severity="error" data-testid="ground-form-error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()} data-testid="save-ground">Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const BlackoutForm = ({ groundId, onClose, onSaved }: { groundId: string; onClose: () => void; onSaved: () => void }) => {
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const add = useMutation({
    mutationFn: () => groundsApi.owner.addBlackout(groundId, { startAt: new Date(start).toISOString(), endAt: new Date(end).toISOString(), reason }),
    onSuccess: onSaved,
    onError: (err) => setError(getApiErrorMessage(err, 'The blackout could not be added.')),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Close the ground for a period</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <TextField size="small" label="From (your device time)" type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} InputLabelProps={{ shrink: true }} />
          <TextField size="small" label="Until (your device time)" type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} InputLabelProps={{ shrink: true }} />
          <TextField size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Typography variant="caption" color="text.secondary">A period that already has a booking is refused: cancel those bookings first.</Typography>
          {error && <Alert severity="error" data-testid="blackout-error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!start || !end || !reason.trim() || add.isPending} onClick={() => add.mutate()} data-testid="save-blackout">Add blackout</Button>
      </DialogActions>
    </Dialog>
  );
};

/** The ground owner's console: your grounds, closing periods, and the bookings at a ground. Restricted by role on the server; this screen only hides itself. */
const OwnerGroundsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, isAuthenticated } = useAuthStore();
  const owner = isAuthenticated && isGroundOwner(user?.roles);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [date, setDate] = useState(localToday());
  const [editing, setEditing] = useState<OwnerGround | 'new' | null>(null);
  const [blackoutFor, setBlackoutFor] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const grounds = useQuery({ queryKey: ['owner-grounds'], queryFn: groundsApi.owner.grounds, enabled: owner });
  const selected = grounds.data?.find((g) => g.ground.id === (selectedId ?? grounds.data?.[0]?.ground.id));
  const bookings = useQuery({ queryKey: ['owner-bookings', selected?.ground.id, date], queryFn: () => groundsApi.owner.bookings(selected!.ground.id, date), enabled: !!selected });
  const blackouts = useQuery({ queryKey: ['owner-blackouts', selected?.ground.id], queryFn: () => groundsApi.owner.blackouts(selected!.ground.id), enabled: !!selected });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['owner-grounds'] }); void queryClient.invalidateQueries({ queryKey: ['owner-bookings'] }); void queryClient.invalidateQueries({ queryKey: ['owner-blackouts'] }); };

  const cancel = useMutation({
    mutationFn: (id: string) => groundsApi.owner.cancel(id),
    onSuccess: (result) => { setNotice(`Booking ${result.booking.bookingNumber} cancelled. ${refundSentence(result)}`); refresh(); },
    onError: (err) => setNotice(getApiErrorMessage(err, 'That booking could not be cancelled.')),
  });
  const noShow = useMutation({
    mutationFn: (id: string) => groundsApi.owner.noShow(id),
    onSuccess: (b: Booking) => { setNotice(`Booking ${b.bookingNumber} marked as a no-show.`); refresh(); },
    onError: (err) => setNotice(getApiErrorMessage(err, 'That booking could not be marked.')),
  });

  if (!owner) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Ground owners only" body="This area is for ground owners. Ask an administrator to give your account the ground-owner role."
      action={<Button variant="contained" onClick={() => navigate('/grounds')}>Browse grounds</Button>} />;
  }
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h3" component="h1">My grounds</Typography>
        <Button variant="contained" onClick={() => setEditing('new')} data-testid="new-ground">New ground</Button>
      </Stack>
      {notice && <Alert severity="info" sx={{ mb: 2 }} onClose={() => setNotice(null)} data-testid="owner-notice">{notice}</Alert>}
      {grounds.isError && <ErrorState body="We couldn't load your grounds." onRetry={() => void grounds.refetch()} />}
      {grounds.data?.length === 0 && <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No grounds yet" body="Add your first ground to start taking bookings." />}

      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        {grounds.data?.map((g) => (
          <Chip key={g.ground.id} label={g.ground.name} color={g.ground.id === selected?.ground.id ? 'primary' : 'default'} onClick={() => setSelectedId(g.ground.id)} data-testid="owner-ground" />
        ))}
      </Stack>

      {selected && (
        <Stack spacing={2}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
              <Box>
                <Typography variant="h6">{selected.ground.name}{!selected.ground.active && <Chip size="small" label="Not bookable" sx={{ ml: 1 }} />}</Typography>
                <Typography variant="body2" color="text.secondary">{selected.ground.address}, {selected.ground.city} · {hhmm(selected.ground.opensAt)}–{hhmm(selected.ground.closesAt)} ({selected.ground.timeZone}) · {selected.ground.slotMinutes} min slots · base {formatPrice(selected.ground.basePrice, selected.ground.currency)}</Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" onClick={() => setEditing(selected)} data-testid="edit-ground">Edit</Button>
                <Button size="small" onClick={() => setBlackoutFor(selected.ground.id)} data-testid="add-blackout">Add blackout</Button>
              </Stack>
            </Stack>
            {blackouts.data && blackouts.data.length > 0 && (
              <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                {blackouts.data.map((b) => <Typography key={b.id} variant="caption" color="text.secondary">Closed {when(b.startAt, selected.ground.timeZone)} – {when(b.endAt, selected.ground.timeZone)}: {b.reason}</Typography>)}
              </Stack>
            )}
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
              <Typography variant="h6">Bookings</Typography>
              <TextField size="small" type="date" value={date} onChange={(e) => setDate(e.target.value)} inputProps={{ 'aria-label': 'Bookings date' }} />
            </Stack>
            {bookings.data?.length === 0 && <Typography variant="body2" color="text.secondary">No bookings that day.</Typography>}
            <Stack spacing={1}>
              {bookings.data?.map((b) => (
                <Stack key={b.id} direction="row" justifyContent="space-between" alignItems="center" data-testid="owner-booking">
                  <Typography variant="body2">{b.bookingNumber} · {when(b.startAt, selected.ground.timeZone)} · {formatPrice(b.price, b.currency)} · <b>{b.status.replace('_', ' ').toLowerCase()}</b></Typography>
                  <Stack direction="row" spacing={1}>
                    {(b.status === 'HELD' || b.status === 'CONFIRMED') && <Button size="small" color="error" onClick={() => cancel.mutate(b.id)} data-testid="owner-cancel">Cancel</Button>}
                    {b.status === 'CONFIRMED' && new Date(b.endAt).getTime() <= Date.now() && <Button size="small" onClick={() => noShow.mutate(b.id)} data-testid="owner-noshow">No show</Button>}
                  </Stack>
                </Stack>
              ))}
            </Stack>
          </Paper>
        </Stack>
      )}

      {editing && (
        <GroundForm
          initial={editing === 'new' ? BLANK : { ...editing.ground, facilities: [...editing.ground.facilities], photoUrls: [...editing.ground.photoUrls] }}
          existing={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />
      )}
      {blackoutFor && <BlackoutForm groundId={blackoutFor} onClose={() => setBlackoutFor(null)} onSaved={() => { setBlackoutFor(null); refresh(); }} />}
    </Box>
  );
};

export default OwnerGroundsPage;
