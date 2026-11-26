import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, FormGroup, MenuItem, Paper, Stack, Switch, TextField, Typography,
} from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { adminApi, venuesApi } from '@/api/venues';
import { getApiErrorMessage } from '@/api/errors';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { localToday, refundSentence, when } from '@/lib/venueSlots';
import type { Booking, Court, CourtInput, ManagedVenue, Sport, VenueInput } from '@/types/venues';

export type ManageMode = 'owner' | 'admin';

const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '');
const toNumber = (v: string) => (v.trim() === '' ? null : Number(v));

const BLANK_VENUE: VenueInput = {
  name: '', address: '', city: '', latitude: null, longitude: null, facilities: [], photoUrls: [], opensAt: '06:00', closesAt: '22:00', timeZone: 'Asia/Kolkata',
};
const BLANK_COURT: CourtInput = { name: '', surface: null, sportIds: [], slotMinutes: 60, basePrice: 800, weekendPrice: null, peakPrice: null, peakFrom: null, peakTo: null };

const venueInput = (m: ManagedVenue): VenueInput => ({ ...m.venue, facilities: [...m.venue.facilities], photoUrls: [...m.venue.photoUrls] });
const courtInput = (c: Court): CourtInput => ({
  name: c.name, surface: c.surface, sportIds: c.sports.map((s) => s.id), slotMinutes: c.slotMinutes, basePrice: c.basePrice, weekendPrice: c.weekendPrice,
  peakPrice: c.peakPrice, peakFrom: c.peakFrom, peakTo: c.peakTo,
});

interface Owner { id: string; email: string }

const VenueForm = ({ mode, existing, owners, onClose, onSaved }: { mode: ManageMode; existing?: ManagedVenue; owners: Owner[]; onClose: () => void; onSaved: () => void }) => {
  const api = mode === 'admin' ? venuesApi.admin : venuesApi.owner;
  const [f, setF] = useState<VenueInput>(existing ? venueInput(existing) : BLANK_VENUE);
  const [active, setActive] = useState(existing?.venue.active ?? true);
  const [ownerId, setOwnerId] = useState(existing?.ownerId ?? '');
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => (existing
      ? api.update(existing.venue.id, f, active, existing.version, mode === 'admin' && ownerId !== existing.ownerId ? ownerId : undefined)
      : api.create(f, mode === 'admin' ? ownerId : undefined)),
    onSuccess: onSaved,
    onError: (err) => setError(getApiErrorMessage(err, 'The venue could not be saved.')),
  });
  const set = <K extends keyof VenueInput>(key: K, value: VenueInput[K]) => setF((p) => ({ ...p, [key]: value }));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{existing ? 'Edit venue' : mode === 'admin' ? 'New venue for an owner' : 'New venue'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {mode === 'admin' && (
            <TextField size="small" select label="Owner" value={ownerId} onChange={(e) => setOwnerId(e.target.value)} required
              helperText={existing && !existing.ownerEligible ? 'The current owner no longer holds the ground-owner role: choose another owner to make this venue bookable again.' : 'Only users who hold the ground-owner role are listed.'}
              SelectProps={{ SelectDisplayProps: { 'aria-label': 'Owner' } as React.HTMLAttributes<HTMLDivElement> }} data-testid="venue-owner">
              {owners.map((o) => <MenuItem key={o.id} value={o.id}>{o.email}</MenuItem>)}
            </TextField>
          )}
          <TextField size="small" label="Name" value={f.name} onChange={(e) => set('name', e.target.value)} required />
          <TextField size="small" label="Address" value={f.address} onChange={(e) => set('address', e.target.value)} required />
          <TextField size="small" label="City" value={f.city} onChange={(e) => set('city', e.target.value)} required />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Opens" type="time" value={hhmm(f.opensAt)} onChange={(e) => set('opensAt', e.target.value)} InputLabelProps={{ shrink: true }} fullWidth />
            <TextField size="small" label="Closes" type="time" value={hhmm(f.closesAt)} onChange={(e) => set('closesAt', e.target.value)} InputLabelProps={{ shrink: true }} fullWidth />
          </Stack>
          <TextField size="small" label="Time zone" value={f.timeZone} onChange={(e) => set('timeZone', e.target.value)} helperText="Opening hours are read in this zone, e.g. Asia/Kolkata" />
          <TextField size="small" label="Facilities (comma separated)" value={f.facilities.join(', ')}
            onChange={(e) => set('facilities', e.target.value.split(',').map((x) => x.trim()).filter(Boolean))} />
          {existing && <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Bookable" />}
          {error && <Alert severity="error" data-testid="venue-form-error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending || (mode === 'admin' && !ownerId)} onClick={() => save.mutate()} data-testid="save-venue">Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const CourtForm = ({ mode, venueId, existing, sports, onClose, onSaved }: { mode: ManageMode; venueId: string; existing?: Court; sports: Sport[]; onClose: () => void; onSaved: () => void }) => {
  const api = mode === 'admin' ? venuesApi.admin : venuesApi.owner;
  const [f, setF] = useState<CourtInput>(existing ? courtInput(existing) : BLANK_COURT);
  const [active, setActive] = useState(existing?.active ?? true);
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => (existing ? api.updateCourt(venueId, existing.id, f, active, existing.version) : api.addCourt(venueId, f)),
    onSuccess: onSaved,
    onError: (err) => setError(getApiErrorMessage(err, 'The court could not be saved.')),
  });
  const set = <K extends keyof CourtInput>(key: K, value: CourtInput[K]) => setF((p) => ({ ...p, [key]: value }));
  const toggle = (id: string) => set('sportIds', f.sportIds.includes(id) ? f.sportIds.filter((x) => x !== id) : [...f.sportIds, id]);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{existing ? 'Edit court' : 'New court'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Court name" value={f.name} onChange={(e) => set('name', e.target.value)} required fullWidth />
            <TextField size="small" label="Surface" value={f.surface ?? ''} onChange={(e) => set('surface', e.target.value || null)} fullWidth />
          </Stack>
          <Box>
            <Typography variant="subtitle2">Sports on this court</Typography>
            <FormGroup row data-testid="court-sports">
              {sports.map((s) => (
                <FormControlLabel key={s.id} label={s.name} control={<Checkbox size="small" checked={f.sportIds.includes(s.id)} onChange={() => toggle(s.id)} />} />
              ))}
            </FormGroup>
            {sports.length === 0 && <Typography variant="caption" color="text.secondary">No bookable sports yet. An administrator can mark sports as bookable.</Typography>}
          </Box>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Slot (minutes)" type="number" value={f.slotMinutes} onChange={(e) => set('slotMinutes', Number(e.target.value))} fullWidth />
            <TextField size="small" label="Base price (₹ per slot)" type="number" value={f.basePrice} onChange={(e) => set('basePrice', Number(e.target.value))} fullWidth />
            <TextField size="small" label="Weekend price" type="number" value={f.weekendPrice ?? ''} onChange={(e) => set('weekendPrice', toNumber(e.target.value))} fullWidth />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Peak price" type="number" value={f.peakPrice ?? ''} onChange={(e) => set('peakPrice', toNumber(e.target.value))} fullWidth />
            <TextField size="small" label="Peak from" type="time" value={hhmm(f.peakFrom)} onChange={(e) => set('peakFrom', e.target.value || null)} InputLabelProps={{ shrink: true }} fullWidth />
            <TextField size="small" label="Peak to" type="time" value={hhmm(f.peakTo)} onChange={(e) => set('peakTo', e.target.value || null)} InputLabelProps={{ shrink: true }} fullWidth />
          </Stack>
          {existing && <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Bookable" />}
          {error && <Alert severity="error" data-testid="court-form-error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending || f.sportIds.length === 0} onClick={() => save.mutate()} data-testid="save-court">Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const BlackoutForm = ({ mode, venue, onClose, onSaved }: { mode: ManageMode; venue: ManagedVenue; onClose: () => void; onSaved: () => void }) => {
  const api = mode === 'admin' ? venuesApi.admin : venuesApi.owner;
  const [courtId, setCourtId] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const add = useMutation({
    mutationFn: () => api.addBlackout(venue.venue.id, { courtId: courtId || null, startAt: new Date(start).toISOString(), endAt: new Date(end).toISOString(), reason }),
    onSuccess: onSaved,
    onError: (err) => setError(getApiErrorMessage(err, 'The blackout could not be added.')),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Close the venue or a court for a period</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <TextField size="small" select label="What to close" value={courtId} onChange={(e) => setCourtId(e.target.value)} data-testid="blackout-court"
            SelectProps={{ displayEmpty: true }} InputLabelProps={{ shrink: true }}>
            <MenuItem value="">The whole venue</MenuItem>
            {venue.courts.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
          </TextField>
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

/**
 * Venues, their courts and the sports each court offers, closing periods and (for owners) the bookings. The same screen serves an owner (their own venues)
 * and an administrator (every venue, on an owner's behalf, with reassignment). The server enforces who may do what; this screen only shows the right controls.
 */
const VenueManager = ({ mode }: { mode: ManageMode }) => {
  const queryClient = useQueryClient();
  const api = mode === 'admin' ? venuesApi.admin : venuesApi.owner;
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [date, setDate] = useState(localToday());
  const [editingVenue, setEditingVenue] = useState<ManagedVenue | 'new' | null>(null);
  const [editingCourt, setEditingCourt] = useState<Court | 'new' | null>(null);
  const [blackoutFor, setBlackoutFor] = useState<ManagedVenue | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const venues = useQuery({ queryKey: ['managed-venues', mode], queryFn: api.venues });
  const sports = useQuery({ queryKey: ['bookable-sports'], queryFn: venuesApi.sports });
  const owners = useQuery({ queryKey: ['ground-owners'], queryFn: adminApi.owners, enabled: mode === 'admin' });
  const selected = venues.data?.find((v) => v.venue.id === (selectedId ?? venues.data?.[0]?.venue.id));
  const bookings = useQuery({ queryKey: ['owner-bookings', selected?.venue.id, date], queryFn: () => venuesApi.owner.bookings(selected!.venue.id, { date }), enabled: mode === 'owner' && !!selected });
  const blackouts = useQuery({ queryKey: ['managed-blackouts', mode, selected?.venue.id], queryFn: () => api.blackouts(selected!.venue.id), enabled: !!selected });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['managed-venues'] });
    void queryClient.invalidateQueries({ queryKey: ['owner-bookings'] });
    void queryClient.invalidateQueries({ queryKey: ['managed-blackouts'] });
  };

  const cancel = useMutation({
    mutationFn: (id: string) => venuesApi.owner.cancel(id),
    onSuccess: (result) => { setNotice(`Booking ${result.booking.bookingNumber} cancelled. ${refundSentence(result)}`); refresh(); },
    onError: (err) => setNotice(getApiErrorMessage(err, 'That booking could not be cancelled.')),
  });
  const noShow = useMutation({
    mutationFn: (id: string) => venuesApi.owner.noShow(id),
    onSuccess: (b: Booking) => { setNotice(`Booking ${b.bookingNumber} marked as a no-show.`); refresh(); },
    onError: (err) => setNotice(getApiErrorMessage(err, 'That booking could not be marked.')),
  });

  const ownerEmail = (id: string) => owners.data?.find((o) => o.id === id)?.email;
  const courtName = (b: Booking) => [b.courtName, b.sportName].filter(Boolean).join(' · ');
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h3" component="h1">{mode === 'admin' ? 'Venues' : 'Owner console'}</Typography>
        <Button variant="contained" onClick={() => setEditingVenue('new')} data-testid="new-venue">New venue</Button>
      </Stack>
      {notice && <Alert severity="info" sx={{ mb: 2 }} onClose={() => setNotice(null)} data-testid="owner-notice">{notice}</Alert>}
      {venues.isError && <ErrorState body="We couldn't load the venues." onRetry={() => void venues.refetch()} />}
      {venues.data?.length === 0 && <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No venues yet" body="Add a venue, then add courts and the sports each one offers." />}

      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        {venues.data?.map((v) => (
          <Chip key={v.venue.id} label={v.ownerEligible ? v.venue.name : `${v.venue.name} · owner removed`} color={v.venue.id === selected?.venue.id ? 'primary' : 'default'}
            onClick={() => setSelectedId(v.venue.id)} data-testid="managed-venue" />
        ))}
      </Stack>

      {selected && (
        <Stack spacing={2}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
              <Box>
                <Typography variant="h6">{selected.venue.name}{!selected.venue.active && <Chip size="small" label="Not bookable" sx={{ ml: 1 }} />}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {selected.venue.address}, {selected.venue.city} · {hhmm(selected.venue.opensAt)}–{hhmm(selected.venue.closesAt)} ({selected.venue.timeZone})
                </Typography>
                {mode === 'admin' && <Typography variant="body2" color="text.secondary" data-testid="venue-owner-line">Owner: {ownerEmail(selected.ownerId) ?? selected.ownerId}</Typography>}
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" onClick={() => setEditingVenue(selected)} data-testid="edit-venue">Edit</Button>
                <Button size="small" onClick={() => setEditingCourt('new')} data-testid="add-court">Add court</Button>
                <Button size="small" onClick={() => setBlackoutFor(selected)} data-testid="add-blackout">Add blackout</Button>
              </Stack>
            </Stack>
            {!selected.ownerEligible && (
              <Alert severity="warning" sx={{ mt: 1.5 }} data-testid="owner-removed"
                action={mode === 'admin' ? <Button color="inherit" size="small" onClick={() => setEditingVenue(selected)} data-testid="reassign-venue">Reassign</Button> : undefined}>
                {mode === 'admin'
                  ? 'This venue\'s owner no longer holds the ground-owner role. It takes no new bookings and is hidden from search until you reassign it to another owner. Existing bookings stay valid.'
                  : 'You no longer hold the ground-owner role, so this venue takes no new bookings. Ask an administrator.'}
              </Alert>
            )}
            {blackouts.data && blackouts.data.length > 0 && (
              <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                {blackouts.data.map((b) => (
                  <Typography key={b.id} variant="caption" color="text.secondary">
                    {b.courtId ? `${selected.courts.find((c) => c.id === b.courtId)?.name ?? 'Court'} closed` : 'Venue closed'} {when(b.startAt, selected.venue.timeZone)} – {when(b.endAt, selected.venue.timeZone)}: {b.reason}
                  </Typography>
                ))}
              </Stack>
            )}
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h6" sx={{ mb: 1 }}>Courts</Typography>
            {selected.courts.length === 0 && <Typography variant="body2" color="text.secondary">No courts yet. Add one and choose the sports it offers.</Typography>}
            <Stack spacing={1}>
              {selected.courts.map((c) => (
                <Stack key={c.id} direction="row" justifyContent="space-between" alignItems="center" data-testid="managed-court">
                  <Box>
                    <Typography variant="body2" fontWeight={700}>{c.name}{c.surface ? ` · ${c.surface}` : ''}{!c.active && ' (not bookable)'}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {c.sports.map((s) => s.name).join(', ')} · {c.slotMinutes} min slots · base {formatPrice(c.basePrice, selected.venue.currency)}
                    </Typography>
                  </Box>
                  <Button size="small" onClick={() => setEditingCourt(c)} data-testid="edit-court">Edit</Button>
                </Stack>
              ))}
            </Stack>
          </Paper>

          {mode === 'owner' && (
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
                <Typography variant="h6">Bookings</Typography>
                <TextField size="small" type="date" value={date} onChange={(e) => setDate(e.target.value)} inputProps={{ 'aria-label': 'Bookings date' }} />
              </Stack>
              {bookings.data?.length === 0 && <Typography variant="body2" color="text.secondary">No bookings that day.</Typography>}
              <Stack spacing={1}>
                {bookings.data?.map((b) => (
                  <Stack key={b.id} direction="row" justifyContent="space-between" alignItems="center" data-testid="owner-booking">
                    <Typography variant="body2">
                      {b.bookingNumber} · {courtName(b)} · {when(b.startAt, selected.venue.timeZone)} · {formatPrice(b.price, b.currency)} · <b>{b.status.replace('_', ' ').toLowerCase()}</b>
                    </Typography>
                    <Stack direction="row" spacing={1}>
                      {(b.status === 'HELD' || b.status === 'CONFIRMED') && <Button size="small" color="error" onClick={() => cancel.mutate(b.id)} data-testid="owner-cancel">Cancel</Button>}
                      {b.status === 'CONFIRMED' && new Date(b.endAt).getTime() <= Date.now() && <Button size="small" onClick={() => noShow.mutate(b.id)} data-testid="owner-noshow">No show</Button>}
                    </Stack>
                  </Stack>
                ))}
              </Stack>
            </Paper>
          )}
        </Stack>
      )}

      {editingVenue && (
        <VenueForm mode={mode} existing={editingVenue === 'new' ? undefined : editingVenue} owners={owners.data ?? []}
          onClose={() => setEditingVenue(null)} onSaved={() => { setEditingVenue(null); refresh(); }} />
      )}
      {editingCourt && selected && (
        <CourtForm mode={mode} venueId={selected.venue.id} existing={editingCourt === 'new' ? undefined : editingCourt} sports={sports.data ?? []}
          onClose={() => setEditingCourt(null)} onSaved={() => { setEditingCourt(null); refresh(); }} />
      )}
      {blackoutFor && <BlackoutForm mode={mode} venue={blackoutFor} onClose={() => setBlackoutFor(null)} onSaved={() => { setBlackoutFor(null); refresh(); }} />}
    </Box>
  );
};

export default VenueManager;
