import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, CardActionArea, CardContent, Chip, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { venuesApi } from '@/api/venues';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { localToday } from '@/lib/venueSlots';

/**
 * Book a court: sport first, then city and date. Only sports the catalogue marks bookable are offered, and only venues with a court for that sport
 * are listed. The choice lives in the URL, so a search can be shared or reloaded. Public: signing in is only needed to hold a slot.
 */
const VenuesPage = () => {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [city, setCity] = useState(params.get('city') ?? '');
  const [date, setDate] = useState(params.get('date') ?? localToday());
  const sportSlug = params.get('sport');

  const sports = useQuery({ queryKey: ['bookable-sports'], queryFn: venuesApi.sports });
  const sport = sports.data?.find((s) => s.slug === sportSlug);
  const appliedCity = params.get('city') ?? '';
  const appliedDate = params.get('date') ?? localToday();

  const results = useQuery({
    queryKey: ['venues', sport?.id, appliedCity, appliedDate],
    queryFn: () => venuesApi.search({ sport: sport!.id, city: appliedCity.trim() || undefined, date: appliedDate || undefined }),
    enabled: !!sport,
  });

  const choose = (slug: string) => setParams({ sport: slug, city: appliedCity, date: appliedDate }, { replace: true });

  return (
    <Box>
      <Typography variant="h3" component="h1">Book a court</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Pick a sport, a city and a day, then choose a court and a time. A slot you hold stays yours for a few minutes while you pay.
      </Typography>

      <Typography variant="subtitle2" sx={{ mb: 0.75 }}>1. Sport</Typography>
      {sports.isError && <ErrorState body="We couldn't load the sports right now." onRetry={() => void sports.refetch()} />}
      {sports.isLoading && <Skeleton variant="rounded" height={36} width={320} />}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2 }} data-testid="sport-chips">
        {sports.data?.map((s) => (
          <Chip key={s.id} label={s.name} color={s.slug === sportSlug ? 'primary' : 'default'} variant={s.slug === sportSlug ? 'filled' : 'outlined'}
            onClick={() => choose(s.slug)} data-testid="sport-chip" aria-pressed={s.slug === sportSlug} />
        ))}
      </Stack>

      <Typography variant="subtitle2" sx={{ mb: 0.75 }}>2. City and day</Typography>
      <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 3 }}
        onSubmit={(e) => { e.preventDefault(); if (sportSlug) setParams({ sport: sportSlug, city, date }, { replace: true }); }}>
        <TextField size="small" label="City" value={city} onChange={(e) => setCity(e.target.value)} inputProps={{ maxLength: 80 }} />
        <TextField size="small" label="Date" type="date" value={date} onChange={(e) => setDate(e.target.value)} InputLabelProps={{ shrink: true }} />
        <Button type="submit" variant="contained" disabled={!sportSlug} data-testid="venues-search">Search</Button>
      </Stack>

      {!sport && !sports.isLoading && !sports.isError && (
        <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Choose a sport to begin" body="We will show venues that have a court for it." />
      )}
      {results.isError && <ErrorState body="We couldn't load venues right now." onRetry={() => void results.refetch()} />}
      {results.isLoading && <Stack spacing={1.5}>{[0, 1, 2].map((i) => <Skeleton key={i} variant="rounded" height={96} />)}</Stack>}
      {results.data && results.data.length === 0 && (
        <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title={`No ${sport?.name ?? ''} courts with free slots`.replace('  ', ' ')} body="Try another date or city." />
      )}
      <Stack spacing={1.5}>
        {results.data?.map(({ venue, courts, availableSlots, lowestAvailablePrice }) => (
          <Card key={venue.id} variant="outlined" data-testid="venue-card">
            <CardActionArea onClick={() => navigate(`/venues/${venue.id}?sport=${sportSlug}&date=${appliedDate}`)}>
              <CardContent>
                <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                  <Box>
                    <Typography variant="h6" component="h2">{venue.name}</Typography>
                    <Typography variant="body2" color="text.secondary">{venue.address}, {venue.city}</Typography>
                    <Stack direction="row" spacing={0.75} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                      {courts.map((c) => <Chip key={c.courtId} size="small" label={c.availableSlots === null ? c.name : `${c.name} · ${c.availableSlots} free`} />)}
                      {venue.facilities.slice(0, 3).map((f) => <Chip key={f} size="small" variant="outlined" label={f} />)}
                    </Stack>
                  </Box>
                  <Box sx={{ textAlign: { sm: 'right' } }}>
                    {availableSlots !== null && lowestAvailablePrice !== null ? (
                      <>
                        <Typography variant="subtitle1" fontWeight={800}>from {formatPrice(lowestAvailablePrice, venue.currency)}</Typography>
                        <Typography variant="caption" color="text.secondary">{availableSlots} slot{availableSlots === 1 ? '' : 's'} free on {appliedDate}</Typography>
                      </>
                    ) : (
                      <Typography variant="caption" color="text.secondary">{courts.length} court{courts.length === 1 ? '' : 's'}</Typography>
                    )}
                  </Box>
                </Stack>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Stack>
    </Box>
  );
};

export default VenuesPage;
