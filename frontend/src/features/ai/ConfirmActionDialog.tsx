import { Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Typography } from '@mui/material';
import { useAiStore } from '@/store/aiStore';

const TITLES: Record<string, string> = {
  create_return: 'Confirm return request', reorder_product: 'Confirm adding to cart',
  create_support_case: 'Confirm support case', add_case_message: 'Confirm message to support',
  request_case_escalation: 'Confirm request for a person to review', reopen_support_case: 'Confirm reopening the case',
};

/** Every state-changing action the assistant proposes stops here: nothing happens until the customer presses Confirm. */
const ConfirmActionDialog = () => {
  const action = useAiStore((s) => s.pendingAction);
  const confirming = useAiStore((s) => s.confirming);
  const confirmPending = useAiStore((s) => s.confirmPending);
  const dismissPending = useAiStore((s) => s.dismissPending);
  return (
    <Dialog open={action !== null} onClose={confirming ? undefined : dismissPending} aria-labelledby="ai-confirm-title" maxWidth="xs" fullWidth>
      <DialogTitle id="ai-confirm-title">{action ? (TITLES[action.tool] ?? 'Confirm action') : ''}</DialogTitle>
      <DialogContent>
        <DialogContentText data-testid="ai-confirm-summary" sx={{ color: 'text.primary' }}>{action?.summary}</DialogContentText>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
          This is checked again against your account right before it happens. Valid for 5 minutes.
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={dismissPending} disabled={confirming}>Cancel</Button>
        <Button variant="contained" onClick={confirmPending} disabled={confirming} data-testid="ai-confirm-button"
          startIcon={confirming ? <CircularProgress size={16} color="inherit" /> : undefined}>
          {action?.label ?? 'Confirm'}
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default ConfirmActionDialog;
