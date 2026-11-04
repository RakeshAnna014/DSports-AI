import { Chip, Paper, Stack, Typography } from '@mui/material';
import { AutoAwesome } from '@mui/icons-material';
import type { AiResponse } from '@/types/ai';
import { formatPrice } from '@/lib/productUtils';

const label = (value: unknown) => String(value).replace(/_/g, ' ');
const capitalise = (value: string) => value.charAt(0).toUpperCase() + value.slice(1);

/** The interpretation the assistant used for a product search, shown so the customer can see (and correct) what it understood. */
export const understoodFilters = (response: AiResponse): { key: string; text: string }[] => {
  if (response.intent !== 'PRODUCT_SEARCH' && response.intent !== 'REFINE_SEARCH') return [];
  const s = response.meta.slots ?? {};
  const rows: { key: string; text: string }[] = [];
  if (s.sport) rows.push({ key: 'sport', text: `Sport: ${capitalise(label(s.sport))}` });
  if (s.category) rows.push({ key: 'category', text: `Product: ${capitalise(label(s.category))}` });
  if (s.query && !s.category) rows.push({ key: 'query', text: `Looking for: ${label(s.query)}` });
  if (s.skill_level) rows.push({ key: 'skill', text: `Experience: ${capitalise(label(s.skill_level))}` });
  if (s.age_group) rows.push({ key: 'age', text: `Age group: ${capitalise(label(s.age_group))}` });
  if (s.weight_pref) rows.push({ key: 'weight', text: `Preference: ${capitalise(label(s.weight_pref))}` });
  if (typeof s.min_price === 'number') rows.push({ key: 'min', text: `From ${formatPrice(s.min_price)}` });
  if (typeof s.max_price === 'number') rows.push({ key: 'max', text: `Budget: up to ${formatPrice(s.max_price)}` });
  return rows;
};

const UnderstoodRequest = ({ response }: { response: AiResponse }) => {
  const filters = understoodFilters(response);
  if (!filters.length) return null;
  return (
    <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5, borderColor: 'secondary.light' }} data-testid="ai-understood">
      <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: 0.75 }}>
        <AutoAwesome fontSize="small" color="secondary" />
        <Typography variant="caption" fontWeight={700} color="secondary.dark">AI understood your request</Typography>
      </Stack>
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.75 }}>
        {filters.map((f) => <Chip key={f.key} size="small" label={f.text} data-testid={`ai-understood-${f.key}`} />)}
      </Stack>
    </Paper>
  );
};

export default UnderstoodRequest;
