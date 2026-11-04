import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { paymentsApi } from '@/api/payments';
import { getApiErrorMessage } from '@/api/errors';
import type { PaymentSummaryResponse, RefundResponse } from '@/types/payments';
import { CAPTURED_STATUSES } from '@/types/payments';
import { formatPrice } from '@/lib/productUtils';

const STATUS_COLORS: Record<string, 'default' | 'info' | 'success' | 'error' | 'warning'> = {
  REQUESTED: 'info',
  PROCESSING: 'warning',
  COMPLETED: 'success',
  FAILED: 'error',
  REJECTED: 'default',
};

interface Props {
  orderId: string;
}

/**
 * Lets a customer ask for a refund of a paid order. The request is reviewed by an administrator; nothing is paid out
 * from here. The idempotency key is kept until a request succeeds, so retrying after a network error cannot create
 * a second request.
 */
const OrderRefundSection = ({ orderId }: Props) => {
  const [payment, setPayment] = useState<PaymentSummaryResponse | null>(null);
  const [refunds, setRefunds] = useState<RefundResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const idempotencyKey = useRef(crypto.randomUUID());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const history = await paymentsApi.getPaymentHistory();
      const captured = history.find((p) => p.orderId === orderId && CAPTURED_STATUSES.includes(p.status)) ?? null;
      setPayment(captured);
      setRefunds(captured ? await paymentsApi.getRefunds(captured.id) : []);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Could not load payment details'));
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async () => {
    if (!payment) return;
    setSubmitting(true);
    setError(null);
    try {
      await paymentsApi.requestRefund(payment.id, { reason: reason.trim() || undefined }, idempotencyKey.current);
      idempotencyKey.current = crypto.randomUUID();
      setDialogOpen(false);
      setReason('');
      await load();
    } catch (err) {
      setError(getApiErrorMessage(err, 'Refund request failed'));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return <CircularProgress size={20} />;
  }
  if (!payment) {
    return null;
  }

  const hasOpenRequest = refunds.some((r) => ['REQUESTED', 'PROCESSING', 'FAILED'].includes(r.status));
  const fullyRefunded = payment.status === 'REFUNDED';

  return (
    <Card variant="outlined" sx={{ borderRadius: 2, mb: 3 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6" fontWeight={600}>Refunds</Typography>
          {!fullyRefunded && !hasOpenRequest && (
            <Button variant="outlined" size="small" onClick={() => setDialogOpen(true)}>
              Request refund
            </Button>
          )}
        </Stack>
        {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
        {refunds.length === 0 ? (
          <Typography variant="body2" color="text.secondary">No refund requests.</Typography>
        ) : (
          <Stack spacing={1}>
            {refunds.map((r) => (
              <Stack key={r.id} direction="row" spacing={2} alignItems="center">
                <Chip size="small" label={r.status} color={STATUS_COLORS[r.status] ?? 'default'} />
                <Typography variant="body2">{formatPrice(r.amount, r.currency)}</Typography>
                {r.reason && <Typography variant="body2" color="text.secondary">{r.reason}</Typography>}
              </Stack>
            ))}
          </Stack>
        )}
        {hasOpenRequest && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Your request is being reviewed.
          </Typography>
        )}
      </CardContent>

      <Dialog open={dialogOpen} onClose={() => !submitting && setDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Request a refund</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Our team will review your request for the remaining {formatPrice(payment.amount, payment.currency)} payment.
          </Typography>
          <TextField
            label="Reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            fullWidth
            multiline
            minRows={2}
            inputProps={{ maxLength: 500 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)} disabled={submitting}>Close</Button>
          <Button variant="contained" onClick={submit} disabled={submitting}>
            {submitting ? <CircularProgress size={20} /> : 'Submit request'}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
};

export default OrderRefundSection;
