import { Alert, Box, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { CheckCircle, HighlightOff } from '@mui/icons-material';
import { formatDate } from '@/lib/productUtils';
import type { AiEligibility, AiSuggestion } from '@/types/ai';

/**
 * The return decision, exactly as the DSports Returns service made it, and the one real next step it supports: start the return
 * (which still needs the customer's confirmation). Refund method, exchange and wallet are NOT offered: DSports has no such APIs;
 * a refund follows the existing refund process once the return is approved.
 */
const ReturnOptions = ({ eligibility, action, onAction, disabled }: {
  eligibility: AiEligibility & { return_deadline?: string | null; delivered_at?: string | null };
  action?: AiSuggestion;
  onAction: (s: AiSuggestion) => void;
  disabled?: boolean;
}) => (
  <Paper variant="outlined" sx={{ p: 2, borderColor: eligibility.eligible ? 'success.light' : 'error.light' }} data-testid="ai-eligibility">
    <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="subtitle1" fontWeight={800} noWrap>{eligibility.product_name ?? 'Return check'}</Typography>
        {eligibility.order_number && <Typography variant="caption" color="text.secondary">Order {eligibility.order_number}</Typography>}
      </Box>
      <Chip icon={eligibility.eligible ? <CheckCircle /> : <HighlightOff />} color={eligibility.eligible ? 'success' : 'error'}
        label={eligibility.eligible ? 'Eligible for return' : 'Not eligible'} />
    </Stack>
    <Typography variant="body2" color="text.secondary">{eligibility.message}</Typography>
    {eligibility.eligible && (
      <Stack spacing={0.25} sx={{ mt: 1 }}>
        {eligibility.max_returnable_quantity != null && <Typography variant="caption">✓ You can return up to <strong>{eligibility.max_returnable_quantity}</strong> unit(s)</Typography>}
        {eligibility.return_deadline && <Typography variant="caption">✓ Return by <strong>{formatDate(eligibility.return_deadline)}</strong></Typography>}
      </Stack>
    )}
    {eligibility.eligible && action && (
      <Box sx={{ mt: 1.5 }}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 0.75 }}>What would you like to do?</Typography>
        <Button variant="contained" disabled={disabled} onClick={() => onAction(action)} data-testid="ai-return-action">{action.label}</Button>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>
          You will be asked to confirm. DSports reviews the return; any refund follows the refund policy once it is approved.
        </Typography>
      </Box>
    )}
    {!eligibility.eligible && <Alert severity="info" sx={{ mt: 1.5 }}>This decision comes from DSports&apos; return rules and cannot be overridden here.</Alert>}
  </Paper>
);

export default ReturnOptions;
