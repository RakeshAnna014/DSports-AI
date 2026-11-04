import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, MenuItem, Skeleton, Stack, TextField, Typography,
} from '@mui/material';
import { FamilyRestroomOutlined, PersonOutline } from '@mui/icons-material';
import { shopperProfilesApi } from '@/api/shopperProfiles';
import { getApiErrorMessage } from '@/api/errors';
import { AGE_BANDS, type AgeBand, type ShopperProfile } from '@/types/shopperProfile';
import PreferencesEditor from './PreferencesEditor';
import { PROFILES_KEY, useSelectProfile, useShopperProfiles } from './useShopperProfiles';

interface Draft { id?: string; name: string; relationship: string; ageBand: AgeBand | '' }
const emptyDraft: Draft = { name: '', relationship: '', ageBand: '' };

const FamilyDialog = ({ draft, onClose, onSaved }: { draft: Draft | null; onClose: () => void; onSaved: (p: ShopperProfile) => void }) => {
  const qc = useQueryClient();
  const [value, setValue] = useState<Draft>(emptyDraft);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { if (draft) { setValue(draft); setError(null); } }, [draft]);
  const save = useMutation({
    mutationFn: () => {
      const payload = { name: value.name.trim(), relationship: value.relationship.trim() || undefined, ageBand: value.ageBand as AgeBand };
      return value.id ? shopperProfilesApi.updateFamily(value.id, payload) : shopperProfilesApi.createFamily(payload);
    },
    onSuccess: (p) => { void qc.invalidateQueries({ queryKey: PROFILES_KEY }); onSaved(p); },
    onError: (e) => setError(getApiErrorMessage(e, 'Could not save this family profile')),
  });
  return (
    <Dialog open={!!draft} onClose={onClose} fullWidth maxWidth="xs" data-testid="family-dialog">
      <DialogTitle>{value.id ? 'Edit family profile' : 'Add a family profile'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField label="Name or nickname" value={value.name} onChange={(e) => setValue({ ...value, name: e.target.value })} required inputProps={{ maxLength: 60, 'data-testid': 'family-name' }} />
          <TextField label="Relationship (optional)" value={value.relationship} onChange={(e) => setValue({ ...value, relationship: e.target.value })} inputProps={{ maxLength: 30 }} />
          <TextField select label="Age band" value={value.ageBand} onChange={(e) => setValue({ ...value, ageBand: e.target.value as AgeBand })} required
            helperText="We keep an age band, never a date of birth." inputProps={{ 'data-testid': 'family-age' }}>
            {AGE_BANDS.map((b) => <MenuItem key={b.value} value={b.value}>{b.label}</MenuItem>)}
          </TextField>
          {error && <Alert severity="warning">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!value.name.trim() || !value.ageBand || save.isPending} onClick={() => save.mutate()} data-testid="family-save">Save</Button>
      </DialogActions>
    </Dialog>
  );
};

/** Your own profile and the family members you manage, with preferences for whichever one is selected. */
const ProfilesPanel = () => {
  const qc = useQueryClient();
  const { data, isLoading, isError } = useShopperProfiles();
  const select = useSelectProfile();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [deleting, setDeleting] = useState<ShopperProfile | null>(null);
  const [error, setError] = useState<string | null>(null);

  const remove = useMutation({
    mutationFn: (id: string) => shopperProfilesApi.deleteFamily(id),
    onSuccess: () => { setDeleting(null); setEditingId(null); void qc.invalidateQueries({ queryKey: PROFILES_KEY }); void qc.invalidateQueries({ queryKey: ['ai-recommendations'] }); void qc.invalidateQueries({ queryKey: ['reco'] }); },
    onError: (e) => { setDeleting(null); setError(getApiErrorMessage(e, 'Could not delete this profile')); },
  });

  if (isLoading) return <Skeleton variant="rounded" height={180} />;
  if (isError || !data) return <Alert severity="warning">Your shopping preferences could not be loaded right now.</Alert>;

  const current = data.profiles.find((p) => p.id === (editingId ?? data.activeProfileId)) ?? data.profiles[0];
  const family = data.profiles.filter((p) => p.kind === 'FAMILY');

  return (
    <Card sx={{ mt: 3 }} data-testid="profiles-panel">
      <CardContent>
        <Typography variant="h6" gutterBottom>Shopping preferences and family</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Tell us what you play and we tune recommendations to it. Family profiles are managed by you as the account holder: they cannot sign in, we keep only an age band,
          and deleting one deletes everything stored for it.
        </Typography>
        {error && <Alert severity="warning" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
          {data.profiles.map((p) => (
            <Chip key={p.id} clickable color={p.id === current.id ? 'primary' : 'default'} variant={p.id === current.id ? 'filled' : 'outlined'}
              icon={p.kind === 'OWNER' ? <PersonOutline /> : <FamilyRestroomOutlined />} onClick={() => setEditingId(p.id)}
              label={`${p.kind === 'OWNER' ? 'Me (account holder)' : `${p.displayName} · family member`}${p.id === data.activeProfileId ? ' · active' : ''}`}
              data-testid={`profile-chip-${p.kind === 'OWNER' ? 'owner' : 'family'}`} />
          ))}
          <Button size="small" startIcon={<FamilyRestroomOutlined />} disabled={family.length >= data.maxFamilyProfiles} onClick={() => setDraft(emptyDraft)} data-testid="family-add">
            Add family member
          </Button>
        </Stack>
        <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 2 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
            <Typography variant="subtitle1" fontWeight={700} sx={{ flexGrow: 1 }}>
              {current.kind === 'OWNER' ? 'Your preferences' : `${current.displayName}'s preferences`}
              {current.kind === 'FAMILY' && current.ageBand ? ` (${AGE_BANDS.find((b) => b.value === current.ageBand)?.label})` : ''}
            </Typography>
            {current.id !== data.activeProfileId
              ? <Button size="small" variant="outlined" disabled={select.isPending} onClick={() => select.mutate(current.id)} data-testid="profile-use">Use this profile for recommendations</Button>
              : <Chip size="small" color="success" label="Active for recommendations" />}
            {current.kind === 'FAMILY' && (
              <>
                <Button size="small" onClick={() => setDraft({ id: current.id, name: current.displayName, relationship: current.relationship ?? '', ageBand: current.ageBand ?? '' })}>Edit</Button>
                <Button size="small" color="error" onClick={() => setDeleting(current)} data-testid="family-delete">Delete</Button>
              </>
            )}
          </Stack>
          <PreferencesEditor profile={current} key={current.id} />
        </Box>
      </CardContent>
      <FamilyDialog draft={draft} onClose={() => setDraft(null)} onSaved={(p) => { setDraft(null); setEditingId(p.id); }} />
      <Dialog open={!!deleting} onClose={() => setDeleting(null)}>
        <DialogTitle>Delete {deleting?.displayName}?</DialogTitle>
        <DialogContent><DialogContentText>This permanently deletes this family profile and all of its preferences. Your own profile and orders are not affected.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleting(null)}>Keep it</Button>
          <Button color="error" variant="contained" disabled={remove.isPending} onClick={() => deleting && remove.mutate(deleting.id)} data-testid="family-delete-confirm">Delete</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
};

export default ProfilesPanel;
