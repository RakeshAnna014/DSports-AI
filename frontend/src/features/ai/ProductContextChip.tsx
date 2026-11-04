import { Chip } from '@mui/material';
import { AutoAwesome } from '@mui/icons-material';
import { useAiStore } from '@/store/aiStore';

/** Compact indicator of what the conversation is about: the product chosen with "Ask AI", or the products being compared. */
const ProductContextChip = () => {
  const productContext = useAiStore((s) => s.productContext);
  const comparisonProducts = useAiStore((s) => s.comparisonProducts);
  const clear = useAiStore((s) => s.clearProductContext);
  if (comparisonProducts.length >= 2) {
    return (
      <Chip icon={<AutoAwesome />} color="secondary" variant="outlined" size="small" data-testid="ai-context"
        label={`Comparing: ${comparisonProducts.map((p) => p.name).join(' vs ')}`} onDelete={clear} sx={{ maxWidth: '100%' }} />
    );
  }
  if (!productContext) return null;
  return (
    <Chip icon={<AutoAwesome />} color="secondary" variant="outlined" size="small" data-testid="ai-context"
      label={`Asking about: ${productContext.name}`} onDelete={clear} sx={{ maxWidth: '100%' }} />
  );
};

export default ProductContextChip;
