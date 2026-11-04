import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Autocomplete, Box, Button, Chip, FormControlLabel, MenuItem, Stack, Switch, TextField, Typography } from '@mui/material';
import { catalogApi } from '@/api/catalog';
import { shopperProfilesApi } from '@/api/shopperProfiles';
import { getApiErrorMessage } from '@/api/errors';
import { PROFILES_KEY } from './useShopperProfiles';
import { SIZE_FIELDS, USAGE_LEVELS, type PreferencesPayload, type ShopperProfile, type UsageLevel } from '@/types/shopperProfile';

const numberOrNull = (v: string) => (v.trim() === '' ? null : Number(v));

/**
 * Every field is optional: leave anything blank and it simply is not used. Sports come from the catalog the shop really sells, brands and
 * categories too; teams are free text. Saving applies to the profile being edited, not necessarily the active one.
 */
const PreferencesEditor = ({ profile }: { profile: ShopperProfile }) => {
  const qc = useQueryClient();
  const sports = useQuery({ queryKey: ['catalog', 'sports'], queryFn: catalogApi.getSports, staleTime: 600_000 });
  const brands = useQuery({ queryKey: ['catalog', 'brands'], queryFn: catalogApi.getBrands, staleTime: 600_000 });
  const categories = useQuery({ queryKey: ['catalog', 'categories'], queryFn: catalogApi.getCategories, staleTime: 600_000 });

  const p = profile.preferences;
  const [form, setForm] = useState({
    sports: p.sports, brands: p.favoriteBrands, categories: p.favoriteCategories, teams: p.favoriteTeams, level: (p.usageLevel ?? '') as UsageLevel | '',
    priceMin: p.priceMin?.toString() ?? '', priceMax: p.priceMax?.toString() ?? '', sizes: p.sizes, personalization: profile.personalizationEnabled,
  });
  const [message, setMessage] = useState<{ text: string; error?: boolean } | null>(null);
  useEffect(() => {
    setForm({ sports: p.sports, brands: p.favoriteBrands, categories: p.favoriteCategories, teams: p.favoriteTeams, level: (p.usageLevel ?? '') as UsageLevel | '',
      priceMin: p.priceMin?.toString() ?? '', priceMax: p.priceMax?.toString() ?? '', sizes: p.sizes, personalization: profile.personalizationEnabled });
    setMessage(null);
  }, [profile.id, profile.updatedAt]);   // eslint-disable-line react-hooks/exhaustive-deps

  const done = (text: string) => () => { setMessage({ text }); void qc.invalidateQueries({ queryKey: PROFILES_KEY }); void qc.invalidateQueries({ queryKey: ['ai-recommendations'] }); void qc.invalidateQueries({ queryKey: ['reco'] }); };
  const fail = (e: unknown) => setMessage({ text: getApiErrorMessage(e, 'Could not save your preferences'), error: true });
  const priceInvalid = form.priceMin !== '' && form.priceMax !== '' && Number(form.priceMin) > Number(form.priceMax);

  const save = useMutation({
    mutationFn: () => {
      const payload: PreferencesPayload = {
        sports: form.sports, favoriteBrands: form.brands, favoriteCategories: form.categories, favoriteTeams: form.teams, usageLevel: form.level,
        priceMin: numberOrNull(form.priceMin), priceMax: numberOrNull(form.priceMax), sizes: form.sizes, personalizationEnabled: form.personalization,
      };
      return shopperProfilesApi.savePreferences(profile.id, payload);
    },
    onSuccess: done('Preferences saved'), onError: fail,
  });
  const reset = useMutation({ mutationFn: () => shopperProfilesApi.resetPreferences(profile.id), onSuccess: done('Preferences cleared'), onError: fail });

  return (
    <Stack spacing={2} data-testid="preferences-editor">
      <Typography variant="body2" color="text.secondary">
        All optional. {profile.kind === 'FAMILY' ? `${profile.displayName}'s` : 'Your'} answers only tune recommendations: nothing here is needed to shop.
      </Typography>
      <Autocomplete multiple filterSelectedOptions options={(sports.data ?? []).map((s) => s.name)} value={form.sports} onChange={(_, v) => setForm({ ...form, sports: v })}
        renderInput={(params) => <TextField {...params} label="Sports" helperText="Only sports DSports sells" />}
        renderTags={(v, getProps) => v.map((o, i) => <Chip {...getProps({ index: i })} key={o} label={o} size="small" />)} data-testid="pref-sports" />
      <Autocomplete multiple filterSelectedOptions options={(brands.data ?? []).map((b) => b.name)} value={form.brands} onChange={(_, v) => setForm({ ...form, brands: v })}
        renderInput={(params) => <TextField {...params} label="Favourite brands" />} renderTags={(v, getProps) => v.map((o, i) => <Chip {...getProps({ index: i })} key={o} label={o} size="small" />)} />
      <Autocomplete multiple filterSelectedOptions options={(categories.data ?? []).map((c) => c.name)} value={form.categories} onChange={(_, v) => setForm({ ...form, categories: v })}
        renderInput={(params) => <TextField {...params} label="Favourite categories" />} renderTags={(v, getProps) => v.map((o, i) => <Chip {...getProps({ index: i })} key={o} label={o} size="small" />)} />
      <Autocomplete multiple freeSolo options={[]} value={form.teams} onChange={(_, v) => setForm({ ...form, teams: v as string[] })}
        renderInput={(params) => <TextField {...params} label="Favourite teams" helperText="Type a team and press Enter" />}
        renderTags={(v, getProps) => v.map((o, i) => <Chip {...getProps({ index: i })} key={o} label={o} size="small" />)} />
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr 1fr' } }}>
        <TextField select label="Usage level" value={form.level} onChange={(e) => setForm({ ...form, level: e.target.value as UsageLevel | '' })}>
          <MenuItem value="">Not specified</MenuItem>
          {USAGE_LEVELS.map((l) => <MenuItem key={l.value} value={l.value}>{l.label}</MenuItem>)}
        </TextField>
        <TextField label="Price from (₹)" type="number" value={form.priceMin} onChange={(e) => setForm({ ...form, priceMin: e.target.value })} inputProps={{ min: 0 }} error={priceInvalid} />
        <TextField label="Price up to (₹)" type="number" value={form.priceMax} onChange={(e) => setForm({ ...form, priceMax: e.target.value })} inputProps={{ min: 0 }}
          error={priceInvalid} helperText={priceInvalid ? 'Must not be below the minimum' : undefined} />
      </Box>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(4, 1fr)' } }}>
        {SIZE_FIELDS.map((f) => (
          <TextField key={f.key} size="small" label={f.label} value={form.sizes[f.key] ?? ''} inputProps={{ maxLength: 60 }}
            onChange={(e) => setForm({ ...form, sizes: { ...form.sizes, [f.key]: e.target.value } })} />
        ))}
      </Box>
      <FormControlLabel control={<Switch checked={form.personalization} onChange={(e) => setForm({ ...form, personalization: e.target.checked })} />}
        label="Use these preferences to personalise recommendations" />
      {message && <Alert severity={message.error ? 'warning' : 'success'} data-testid="preferences-message">{message.text}</Alert>}
      <Stack direction="row" spacing={1}>
        <Button variant="contained" disabled={save.isPending || priceInvalid} onClick={() => save.mutate()} data-testid="preferences-save">Save preferences</Button>
        <Button disabled={reset.isPending || p.empty} onClick={() => reset.mutate()} data-testid="preferences-reset">Reset to nothing</Button>
      </Stack>
    </Stack>
  );
};

export default PreferencesEditor;
