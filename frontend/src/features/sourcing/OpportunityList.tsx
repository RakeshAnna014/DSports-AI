import { useState } from 'react';
import {
  Alert, Box, Button, Card, CardActionArea, CardContent, Chip, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Switch, Table, TableBody, TableCell, TableHead,
  TablePagination, TableRow, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type Opportunity, type OppFilters, type OppStatus } from '@/api/sourcing';
import { MatchChip, ProductImage, ProvenanceChip, StatusChip, day, pretty } from './parts';

const STATUSES: OppStatus[] = ['DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'APPROVED_OPPORTUNITY', 'SUPPLIER_RESEARCH', 'QUOTE_COMPARISON', 'APPROVED_FOR_ONBOARDING', 'NEEDS_MORE_EVIDENCE', 'ON_HOLD', 'REJECTED'];

const Sel = ({ id, label, value, onChange, options }: { id: string; label: string; value: string; onChange: (v: string) => void; options: [string, string][] }) => (
  <FormControl size="small" sx={{ minWidth: 140 }}>
    <InputLabel id={`${id}-l`}>{label}</InputLabel>
    <Select labelId={`${id}-l`} label={label} value={value} onChange={(e) => onChange(e.target.value)} SelectDisplayProps={{ 'data-testid': id } as React.HTMLAttributes<HTMLDivElement>}>
      <MenuItem value="">Any</MenuItem>{options.map(([v, t]) => <MenuItem key={v} value={v}>{t}</MenuItem>)}
    </Select>
  </FormControl>
);

const score = (o: Opportunity) => (o.opp_score == null ? 'Not enough evidence to score' : `Score ${o.opp_score.toFixed(0)}`);
const marginLabel = (o: Opportunity) => {
  if (o.margin == null) return 'No margin: no quotation yet';
  const quoted = o.calc_basis?.label?.startsWith('Quoted');
  return `${quoted ? 'Margin (quoted cost)' : 'Margin (estimate)'} ${o.margin.toFixed(1)}%`;
};

/** One product as a card: the picture (or an honest placeholder), identity, status and only the numbers that exist. */
const ProductCard = ({ o, onOpen }: { o: Opportunity; onOpen: (id: string) => void }) => (
  <Card variant="outlined" data-testid="opp-row" data-id={o.id} sx={{ height: '100%' }}>
    <CardActionArea onClick={() => onOpen(o.id)} sx={{ height: '100%', alignItems: 'stretch', display: 'flex', flexDirection: 'column', justifyContent: 'flex-start' }}>
      <Box sx={{ p: 1, width: '100%' }}>
        <ProductImage url={o.image_url} name={o.name} level={o.image_match_level} size={150} />
        <Box sx={{ mt: 0.5, minHeight: 24 }}>{o.image_url ? <MatchChip level={o.image_match_level} /> : null}</Box>
      </Box>
      <CardContent sx={{ pt: 0, width: '100%' }}>
        <Typography variant="body2" fontWeight={800} noWrap title={o.name} data-testid="card-name">{o.name}</Typography>
        <Typography variant="caption" color="text.secondary" display="block" noWrap>{[o.brand, o.model ?? o.mfr_sku].filter(Boolean).join(' · ') || 'brand / model not known'}</Typography>
        <Typography variant="caption" color="text.secondary" display="block" noWrap>{[o.sport, o.category].filter(Boolean).join(' / ') || '—'}</Typography>
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ my: 0.75 }}>
          <StatusChip status={o.status} /><ProvenanceChip provenance={o.provenance} />
          {o.catalogue_match?.level && o.catalogue_match.level !== 'NONE' && <Chip size="small" variant="outlined" color="warning" label={`Catalogue: ${pretty(o.catalogue_match.level)}`} />}
        </Stack>
        <Typography variant="caption" display="block">{score(o)}</Typography>
        <Typography variant="caption" display="block" color="text.secondary">{marginLabel(o)}</Typography>
        <Typography variant="caption" display="block" color="text.secondary">{o.quote_count ? `${o.quote_count} supplier offer(s)` : 'No supplier offer yet'} · seen {day(o.last_verified ?? o.first_seen)}</Typography>
      </CardContent>
    </CardActionArea>
  </Card>
);

/** Searchable, filterable product discovery. Filters and sorting are applied by the service (server side), so the page count is the real count. Demonstration and test data are hidden unless asked for. */
const OpportunityList = ({ onOpen, onCreate }: { onOpen: (id: string) => void; onCreate: () => void }) => {
  const [f, setF] = useState<OppFilters>({ size: 20, page: 0, sort: 'updated' });
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const set = (patch: Partial<OppFilters>) => setF((x) => ({ ...x, ...patch, page: 0 }));
  const q = useQuery({ queryKey: ['src', 'opps', f], queryFn: () => sourcingApi.opportunities(f), retry: false });
  const data = q.data;
  const tri = (v: boolean | undefined) => (v === undefined ? '' : v ? 'yes' : 'no');
  const fromTri = (v: string) => (v === '' ? undefined : v === 'yes');
  return (
    <Stack spacing={1.5} data-testid="src-opportunities">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <TextField size="small" label="Search name, brand or model" value={f.q ?? ''} onChange={(e) => set({ q: e.target.value })} inputProps={{ 'data-testid': 'opp-q' }} sx={{ minWidth: 220 }} />
        <Sel id="opp-status" label="Status" value={f.status?.[0] ?? ''} onChange={(v) => set({ status: v ? [v] : undefined })} options={STATUSES.map((s) => [s, pretty(s)])} />
        <Sel id="opp-image" label="Image" value={tri(f.has_image)} onChange={(v) => set({ has_image: fromTri(v) })} options={[['yes', 'Has a valid image'], ['no', 'No image']]} />
        <Sel id="opp-image-match" label="Image match" value={f.image_match ?? ''} onChange={(v) => set({ image_match: (v || undefined) as OppFilters['image_match'] })}
          options={[['EXACT_MODEL', 'Exact model'], ['LIKELY_MATCH', 'Likely match'], ['SIMILAR_REFERENCE', 'Reference only']]} />
        <Sel id="opp-catalogue" label="Catalogue match" value={f.catalogue_match ?? ''} onChange={(v) => set({ catalogue_match: v || undefined })}
          options={[['NONE', 'Not in catalogue'], ['EXACT', 'Exact match'], ['PROBABLE_DUPLICATE', 'Probable duplicate'], ['SIMILAR', 'Similar'], ['REVIEW', 'Needs review']]} />
        <Sel id="opp-evidence" label="Evidence" value={f.evidence ?? ''} onChange={(v) => set({ evidence: v || undefined })} options={[['fresh', 'Checked in last 30 days'], ['stale', 'Older or unchecked']]} />
        <Sel id="opp-supplier" label="Suppliers" value={tri(f.has_supplier)} onChange={(v) => set({ has_supplier: fromTri(v) })} options={[['yes', 'Has offers'], ['no', 'No offers']]} />
        <Sel id="opp-supplier-stage" label="Supplier verification" value={f.supplier_verification ?? ''} onChange={(v) => set({ supplier_verification: (v || undefined) as OppFilters['supplier_verification'] })}
          options={[['DISCOVERED', 'Discovered only'], ['WEBSITE_CHECKED', 'Website checked'], ['IDENTITY_VERIFIED', 'Identity verified'], ['BRAND_AUTHORISATION_VERIFIED', 'Brand authorised']]} />
        <Sel id="opp-has-margin" label="Margin" value={tri(f.has_margin)} onChange={(v) => set({ has_margin: fromTri(v) })} options={[['yes', 'Has a saved margin'], ['no', 'No margin yet']]} />
        <Sel id="opp-risk" label="Supplier risk" value={f.risk ?? ''} onChange={(v) => set({ risk: v || undefined })} options={[['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High'], ['UNKNOWN', 'Unknown']]} />
        <Sel id="opp-confidence" label="Evidence confidence" value={f.confidence ?? ''} onChange={(v) => set({ confidence: v || undefined })} options={[['HIGH', 'High'], ['MEDIUM', 'Medium'], ['LOW', 'Low']]} />
        <TextField size="small" type="number" label="Min score" value={f.min_score ?? ''} onChange={(e) => set({ min_score: e.target.value === '' ? undefined : Number(e.target.value) })} sx={{ width: 100 }} />
        <TextField size="small" type="number" label="Min margin %" value={f.min_margin ?? ''} onChange={(e) => set({ min_margin: e.target.value === '' ? undefined : Number(e.target.value) })} inputProps={{ 'data-testid': 'opp-min-margin' }} sx={{ width: 120 }} />
        <TextField size="small" label="Sport" value={f.sport ?? ''} onChange={(e) => set({ sport: e.target.value })} sx={{ width: 120 }} />
        <TextField size="small" label="Category" value={f.category ?? ''} onChange={(e) => set({ category: e.target.value })} sx={{ width: 120 }} />
        <TextField size="small" label="Brand" value={f.brand ?? ''} onChange={(e) => set({ brand: e.target.value })} sx={{ width: 120 }} />
        <Sel id="opp-sort" label="Sort" value={f.sort ?? 'updated'} onChange={(v) => set({ sort: v || 'updated' })} options={[['updated', 'Recently updated'], ['first_seen', 'First seen'], ['name', 'Name'], ['score', 'Opportunity score'], ['margin', 'Margin']]} />
        <FormControlLabel control={<Switch size="small" checked={!!f.include_fixtures} onChange={(e) => set({ include_fixtures: e.target.checked || undefined })} inputProps={{ 'data-testid': 'opp-fixtures' } as React.InputHTMLAttributes<HTMLInputElement>} />}
          label={<Typography variant="caption">Show demo, test and unclassified data</Typography>} />
        <ToggleButtonGroup size="small" exclusive value={view} onChange={(_e, v: 'grid' | 'table' | null) => v && setView(v)} aria-label="View">
          <ToggleButton value="grid" data-testid="view-grid">Cards</ToggleButton><ToggleButton value="table" data-testid="view-table">Table</ToggleButton>
        </ToggleButtonGroup>
        <Button size="small" variant="outlined" onClick={onCreate} data-testid="opp-create">Add a product</Button>
      </Stack>
      {data && !f.include_fixtures && <Typography variant="caption" color="text.secondary" data-testid="opp-scope">Live data only. Demonstration, test and unclassified records are hidden and are not counted anywhere on this page.</Typography>}
      {q.isLoading && <Skeleton variant="rounded" height={180} />}
      {q.isError && <Alert severity="warning" data-testid="opp-error">{sourcingError(q.error)}</Alert>}
      {data && (
        <Paper variant="outlined" sx={{ p: view === 'grid' ? 1.5 : 0 }}>
          {view === 'grid' ? (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 1.5 }} data-testid="opp-grid">
              {data.items.map((o) => <ProductCard key={o.id} o={o} onOpen={onOpen} />)}
            </Box>
          ) : (
            <Table size="small">
              <TableHead><TableRow><TableCell>Product</TableCell><TableCell>Sport / category</TableCell><TableCell>Provenance</TableCell><TableCell>Status</TableCell><TableCell>Score</TableCell><TableCell>Margin</TableCell><TableCell>Last checked</TableCell></TableRow></TableHead>
              <TableBody>
                {data.items.map((o) => (
                  <TableRow key={o.id} hover onClick={() => onOpen(o.id)} sx={{ cursor: 'pointer' }} data-testid="opp-row" data-id={o.id}>
                    <TableCell><Typography variant="body2" fontWeight={700}>{o.name}</Typography><Typography variant="caption" color="text.secondary">{[o.brand, o.model ?? o.mfr_sku].filter(Boolean).join(' · ') || 'brand / model not known'}</Typography></TableCell>
                    <TableCell>{[o.sport, o.category].filter(Boolean).join(' / ') || '—'}</TableCell>
                    <TableCell><ProvenanceChip provenance={o.provenance} /></TableCell>
                    <TableCell><StatusChip status={o.status} /></TableCell>
                    <TableCell>{o.opp_score == null ? <Chip size="small" variant="outlined" label="Not enough evidence" /> : o.opp_score.toFixed(0)}</TableCell>
                    <TableCell>{o.margin == null ? '—' : `${o.margin.toFixed(1)}%`}</TableCell>
                    <TableCell>{day(o.last_verified)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {data.items.length === 0 && <Box sx={{ p: 3, textAlign: 'center' }} data-testid="opp-empty"><Typography color="text.secondary">No products match. Change the filters, run research from the Research runs tab, or add a product by hand.</Typography></Box>}
          <TablePagination component="div" count={data.total} page={data.page} rowsPerPage={data.size} rowsPerPageOptions={[10, 20, 50]} onPageChange={(_, p) => setF((x) => ({ ...x, page: p }))}
            onRowsPerPageChange={(e) => setF((x) => ({ ...x, size: Number(e.target.value), page: 0 }))} data-testid="opp-pagination" />
        </Paper>
      )}
    </Stack>
  );
};

export default OpportunityList;
