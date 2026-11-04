import { useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type Opportunity } from '@/api/sourcing';
import { ProvenanceChip, StatusChip, day } from './parts';

const LinkDialog = ({ gap, onClose, onDone }: { gap: Opportunity; onClose: () => void; onDone: () => void }) => {
  const [q, setQ] = useState('');
  const [pick, setPick] = useState('');
  const products = useQuery({ queryKey: ['src', 'link-candidates', q], queryFn: () => sourcingApi.opportunities({ q: q || undefined, record_kind: 'PRODUCT_CANDIDATE', size: 20 }), retry: false });
  const link = useMutation({ mutationFn: () => sourcingApi.linkGap(gap.id, pick), onSuccess: () => { onDone(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="link-dialog">
      <DialogTitle>Link “{gap.name}” to a real product</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Alert severity="info" icon={false}>Only link a demand signal to a product that really answers it. The link is audited, and the demand figures then count towards that product.</Alert>
        <TextField size="small" label="Search live products" value={q} onChange={(e) => setQ(e.target.value)} inputProps={{ 'data-testid': 'link-q' }} />
        <TextField select size="small" label="Product" value={pick} onChange={(e) => setPick(e.target.value)} inputProps={{ 'data-testid': 'link-pick' }}>
          {(products.data?.items ?? []).map((p) => <MenuItem key={p.id} value={p.id}>{p.name}{p.brand ? ` — ${p.brand}` : ''}</MenuItem>)}
        </TextField>
        {link.isError && <Alert severity="warning">{sourcingError(link.error)}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!pick || link.isPending} onClick={() => link.mutate()} data-testid="link-save">Link</Button></DialogActions>
    </Dialog>
  );
};

/** Customer searches that found nothing. They are demand signals, not products: no supplier, score or margin exists for them until a real product is found and linked. */
const DemandGaps = ({ onOpen }: { onOpen: (id: string) => void }) => {
  const qc = useQueryClient();
  const [linking, setLinking] = useState<Opportunity | null>(null);
  const q = useQuery({ queryKey: ['src', 'gaps'], queryFn: () => sourcingApi.opportunities({ record_kind: 'DEMAND_GAP', size: 50 }), retry: false });
  if (q.isLoading) return <Skeleton variant="rounded" height={120} />;
  if (q.isError) return <Alert severity="warning" data-testid="gaps-error">{sourcingError(q.error)}</Alert>;
  const items = q.data!.items;
  return (
    <Stack spacing={1.25} data-testid="src-gaps">
      <Alert severity="info" icon={false}>These are things customers searched for and the shop could not find. They are <b>demand signals, not products</b>: they have no price, supplier, margin or score until you find a real product that answers them.</Alert>
      {items.length === 0 && <Alert severity="info" data-testid="gaps-empty">No unmet customer demand has been recorded.</Alert>}
      {items.map((g) => (
        <Paper key={g.id} variant="outlined" sx={{ p: 1.25 }} data-testid="gap-row">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography fontWeight={800}>{g.name}</Typography><StatusChip status={g.status} /><ProvenanceChip provenance={g.provenance} />
            <Box sx={{ flex: 1 }} />
            <Button size="small" onClick={() => onOpen(g.id)} data-testid="gap-open">Open</Button>
            {!g.linked_candidate_id && <Button size="small" variant="outlined" onClick={() => setLinking(g)} data-testid="gap-link">Link to a product</Button>}
          </Stack>
          <Typography variant="caption" color="text.secondary">{g.trend_note ?? 'Customer searches'} · first seen {day(g.first_seen)}{g.linked_candidate_id ? ' · linked to a product' : ' · awaiting product discovery'}</Typography>
        </Paper>
      ))}
      {linking && <LinkDialog gap={linking} onClose={() => setLinking(null)} onDone={() => void qc.invalidateQueries({ queryKey: ['src'] })} />}
    </Stack>
  );
};

export default DemandGaps;
