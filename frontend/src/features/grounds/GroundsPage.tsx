import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, CardActionArea, CardContent, Chip, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { groundsApi } from '@/api/grounds';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatPrice } from '@/lib/productUtils';
import { localToday } from '@/lib/groundSlots';



/** Browse cricket grounds, optionally by city and date. Public: signing in is only needed to book. */
const GroundsPage = () => {
  const navigate = useNavigate();
  const [city, setCity] = useState('');
  const [date, setDate] = useState(localToday());
  const [applied, setApplied] = useState({ city: '', date: localToday() });
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['grounds', applied],
    queryFn: () => groundsApi.search({ city: applied.city.trim() || undefined, date: applied.date || undefined }),
  });

  return (
    <Box>
      <Typography variant="h3" component="h1">Book a ground</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Find a cricket ground, pick a slot and pay to confirm it. A slot you hold stays yours for a few minutes while you pay.</Typography>
      <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 3 }}
        onSubmit={(e) => { e.preventDefault(); setApplied({ city, date }); }}>
        <TextField size="small" label="City" value={city} onChange={(e) => setCity(e.target.value)} inputProps={{ maxLength: 80 }} />
        <TextField size="small" label="Date" type="date" value={date} onChange={(e) => setDate(e.target.value)} InputLabelProps={{ shrink: true }} />
        <Button type="submit" variant="contained" data-testid="grounds-search">Search</Button>
      </Stack>

      {isError && <ErrorState body="We couldn't load grounds right now." onRetry={() => void refetch()} />}
      {isLoading && <Stack spacing={1.5}>{[0, 1, 2].map((i) => <Skeleton key={i} variant="rounded" height={96} />)}</Stack>}
      {data && data.length === 0 && (
        <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No grounds with free slots" body="Try another date or city." />
      )}
      <Stack spacing={1.5}>
        {data?.map(({ ground, availableSlots, lowestAvailablePrice }) => (
          <Card key={ground.id} variant="outlined" data-testid="ground-card">
            <CardActionArea onClick={() => navigate(`/grounds/${ground.id}?date=${applied.date}`)}>
              <CardContent>
                <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                  <Box>
                    <Typography variant="h6" component="h2">{ground.name}</Typography>
                    <Typography variant="body2" color="text.secondary">{ground.address}, {ground.city}</Typography>
                    <Stack direction="row" spacing={0.75} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                      <Chip size="small" label={ground.pitchType.replace('_', ' ').toLowerCase()} />
                      {ground.facilities.slice(0, 4).map((f) => <Chip key={f} size="small" variant="outlined" label={f} />)}
                    </Stack>
                  </Box>
                  <Box sx={{ textAlign: { sm: 'right' } }}>
                    {availableSlots !== null && lowestAvailablePrice !== null ? (
                      <>
                        <Typography variant="subtitle1" fontWeight={800}>from {formatPrice(lowestAvailablePrice, ground.currency)}</Typography>
                        <Typography variant="caption" color="text.secondary">{availableSlots} slot{availableSlots === 1 ? '' : 's'} free on {applied.date}</Typography>
                      </>
                    ) : (
                      <Typography variant="subtitle1" fontWeight={800}>from {formatPrice(ground.basePrice, ground.currency)}</Typography>
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

export default GroundsPage;
