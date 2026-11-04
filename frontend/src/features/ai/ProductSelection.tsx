import { Box, Button, Paper, Stack, Typography } from '@mui/material';
import { formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { colors, motion, shadows } from '@/design/tokens';
import type { AiSelection } from '@/types/ai';

/**
 * "Which product would you like to return?" as clickable cards. The customer never types "the second one": each option carries an
 * opaque choice id that the service resolves to the exact order line it offered.
 */
const ProductSelection = ({ selection, onChoose, disabled }: { selection: AiSelection; onChoose: (choiceId: string, label: string) => void; disabled?: boolean }) => (
  <Box data-testid="ai-selection">
    <Typography variant="subtitle1" fontWeight={800} sx={{ mb: 1 }}>{selection.prompt}</Typography>
    <Box sx={{ display: 'grid', gap: 1.25, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' } }}>
      {selection.options.map((o) => (
        <Paper key={o.choice_id} variant="outlined" data-testid="ai-selection-option"
          sx={{ display: 'flex', gap: 1.5, p: 1.25, alignItems: 'center', borderColor: colors.line, transition: `box-shadow ${motion.base}, transform ${motion.base}`,
            '&:hover': { boxShadow: shadows.cardHover, transform: 'translateY(-2px)' } }}>
          <ProductImage productId={o.product_id} alt={o.name} sx={{ width: 84, flexShrink: 0, borderRadius: 2 }} />
          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="subtitle2" fontWeight={700} noWrap title={o.name}>{o.name}</Typography>
            <Typography variant="caption" color="text.secondary" display="block">Order {o.order_number} · Qty {o.quantity}</Typography>
            {o.unit_price != null && <Typography variant="body2" color="primary.dark" fontWeight={800}>{formatPrice(o.unit_price)}</Typography>}
          </Box>
          <Stack>
            <Button variant="contained" size="small" disabled={disabled} onClick={() => onChoose(o.choice_id, o.name)} aria-label={`Select ${o.name}`} data-testid="ai-select-button">Select</Button>
          </Stack>
        </Paper>
      ))}
    </Box>
  </Box>
);

export default ProductSelection;
