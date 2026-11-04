import { Box, Button, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { useCartStore } from '@/store/cartStore';
import { useUiStore } from '@/store/uiStore';
import ProductImage from '@/components/common/ProductImage';
import { colors } from '@/design/tokens';
import type { AiComparison } from '@/types/ai';

const MISSING = 'Not available in the product data';
const money = (s: string | undefined): number => Number((s ?? '').replace(/[^\d.]/g, '')) || 0;

/** Side-by-side comparison. Every value comes from DSports ("Not available in the product data" when it is not there). */
const ComparisonTable = ({ comparison }: { comparison: AiComparison }) => {
  const addItem = useCartStore((s) => s.addItem);
  const noteAdded = useUiStore((s) => s.noteAdded);
  const priceRow = comparison.rows.find((r) => r.attribute === 'Price');
  return (
    <Paper variant="outlined" sx={{ overflow: 'hidden' }} data-testid="ai-comparison">
      <TableContainer sx={{ maxHeight: 440 }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              <TableCell sx={{ bgcolor: colors.surface }} />
              {comparison.products.map((p) => (
                <TableCell key={p.product_id} sx={{ minWidth: 130, bgcolor: colors.surface, verticalAlign: 'top' }}>
                  <ProductImage src={p.image_url} productId={p.product_id} alt={p.name} sx={{ width: 72, borderRadius: 2, mb: 0.5 }} />
                  <Typography variant="subtitle2" fontWeight={800}>{p.name}</Typography>
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {comparison.rows.map((row) => (
              <TableRow key={row.attribute} hover>
                <TableCell component="th" scope="row" sx={{ fontWeight: 700, color: 'text.secondary' }}>{row.attribute}</TableCell>
                {row.values.map((value, i) => (
                  <TableCell key={`${row.attribute}-${i}`} sx={row.attribute === 'Price' ? { fontWeight: 800, color: 'primary.dark' } : undefined}>
                    {value === MISSING ? <Typography variant="caption" color="text.disabled" sx={{ fontStyle: 'italic' }}>{value}</Typography> : <Box component="span">{value}</Box>}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Stack direction="row" spacing={1} sx={{ p: 1.25, flexWrap: 'wrap', rowGap: 1, borderTop: `1px solid ${colors.line}` }}>
        {comparison.products.map((p, i) => (
          <Button key={p.product_id} size="small" variant="contained" data-testid="ai-compare-add"
            onClick={async () => { await addItem({ productId: p.product_id, name: p.name, price: money(priceRow?.values[i]), imageUrl: p.image_url ?? '' }, 1); noteAdded({ productId: p.product_id, name: p.name }); }}>
            Add {p.name.split(' - ')[0]} to Cart
          </Button>
        ))}
      </Stack>
    </Paper>
  );
};

export default ComparisonTable;
