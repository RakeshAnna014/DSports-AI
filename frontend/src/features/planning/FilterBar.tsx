import { FormControl, InputLabel, MenuItem, Select, Stack, TextField } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { planningApi, type Filters } from '@/api/planning';
import { usePlanning } from './parts';

/** One filter bar for every planning view; its values live in the shared context, so a filter chosen on one tab is still there on the next. */
const FilterBar = ({ show = ['sport', 'category', 'region', 'store', 'q'] }: { show?: ('sport' | 'category' | 'region' | 'store' | 'q')[] }) => {
  const { filters, setFilters, taxonomy } = usePlanning();
  const locs = useQuery({ queryKey: ['pln', 'locations'], queryFn: planningApi.locations, retry: false });
  const regions = (locs.data?.items ?? []).filter((l) => l.level === 'REGION');
  const stores = (locs.data?.items ?? []).filter((l) => l.level === 'STORE' && l.channel === 'STORE');
  const set = (patch: Partial<Filters>) => setFilters({ ...filters, ...patch });
  const cats = filters.sport ? taxonomy[filters.sport] ?? [] : [...new Set(Object.values(taxonomy).flat())].sort();
  const sel = (id: string, label: string, value: string, onChange: (v: string) => void, options: [string, string][]) => (
    <FormControl size="small" sx={{ minWidth: 140 }}>
      <InputLabel id={`${id}-l`}>{label}</InputLabel>
      <Select labelId={`${id}-l`} label={label} value={value} onChange={(e) => onChange(e.target.value)} SelectDisplayProps={{ 'data-testid': id } as React.HTMLAttributes<HTMLDivElement>}>
        <MenuItem value="">All</MenuItem>{options.map(([v, t]) => <MenuItem key={v} value={v}>{t}</MenuItem>)}
      </Select>
    </FormControl>
  );
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap data-testid="filter-bar">
      {show.includes('sport') && sel('f-sport', 'Sport', filters.sport ?? '', (v) => set({ sport: v || undefined, category: undefined }), Object.keys(taxonomy).map((s) => [s, s]))}
      {show.includes('category') && sel('f-category', 'Category', filters.category ?? '', (v) => set({ category: v || undefined }), cats.map((c) => [c, c]))}
      {show.includes('region') && sel('f-region', 'Region', filters.region ?? '', (v) => set({ region: v || undefined, store: undefined }), regions.map((r) => [r.id, r.name]))}
      {show.includes('store') && sel('f-store', 'Store', filters.store?.[0] ?? '', (v) => set({ store: v ? [v] : undefined }), stores.filter((s) => !filters.region || s.parent_id?.startsWith(filters.region)).map((s) => [s.id, s.name]))}
      {show.includes('q') && <TextField size="small" label="Product or SKU" value={filters.q ?? ''} onChange={(e) => set({ q: e.target.value || undefined })} inputProps={{ 'data-testid': 'f-q' }} sx={{ minWidth: 180 }} />}
    </Stack>
  );
};

export default FilterBar;
