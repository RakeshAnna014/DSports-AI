import { Box, Button, Chip, Divider, Paper, Stack, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useAiStore } from '@/store/aiStore';
import ProductImage from '@/components/common/ProductImage';
import { formatDate, formatPrice } from '@/lib/productUtils';
import type { AiCart, AiEligibility, AiOrder, AiPayment, AiRefundGroup, AiReviews, AiTracking } from '@/types/ai';

const statusColor = (status: string): 'success' | 'warning' | 'error' | 'info' | 'default' => {
  if (['DELIVERED', 'SUCCESS', 'COMPLETED', 'APPROVED', 'CONFIRMED'].includes(status)) return 'success';
  if (['CANCELLED', 'FAILED', 'REJECTED'].includes(status)) return 'error';
  if (['PENDING_PAYMENT', 'REQUESTED', 'PROCESSING', 'PARTIALLY_REFUNDED'].includes(status)) return 'warning';
  if (['SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'PICKED_UP'].includes(status)) return 'info';
  return 'default';
};

export const StatusChip = ({ status }: { status: string }) => (
  <Chip size="small" color={statusColor(status)} label={status.replace(/_/g, ' ')} />
);

export const OrderCards = ({ orders, onNavigate }: { orders: AiOrder[]; onNavigate: () => void }) => {
  const navigate = useNavigate();
  const send = useAiStore((s) => s.send);
  return (
    <Stack spacing={1} data-testid="ai-orders">
      {orders.map((o) => (
        <Paper key={o.order_id} variant="outlined" sx={{ p: 1.5 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Typography variant="subtitle2">{o.order_number}</Typography>
            <StatusChip status={o.status} />
          </Stack>
          <Typography variant="caption" color="text.secondary">
            {o.placed_at ? formatDate(o.placed_at) : ''}{o.total !== undefined ? ` · ${formatPrice(o.total, o.currency)}` : ''}
          </Typography>
          {o.items?.map((i) => (
            <Stack key={`${o.order_id}-${i.product_id}`} direction="row" spacing={1} alignItems="center" sx={{ mt: 0.75 }}>
              <ProductImage productId={i.product_id} alt={i.name} sx={{ width: 48, flexShrink: 0, borderRadius: 1.5 }} />
              <Typography variant="body2">{i.quantity} × {i.name} — {formatPrice(i.unit_price)}</Typography>
            </Stack>
          ))}
          <Stack direction="row" spacing={0.75} sx={{ mt: 1, flexWrap: 'wrap', rowGap: 0.5 }}>
            <Button size="small" variant="outlined" onClick={() => { onNavigate(); navigate(`/orders/${o.order_id}`); }}>View order</Button>
            <Button size="small" variant="outlined" onClick={() => send(`Where is order ${o.order_number}?`)} data-testid="ai-order-track">Track</Button>
            <Button size="small" variant="outlined" onClick={() => send(`Reorder from order ${o.order_number}`)} data-testid="ai-order-reorder">Reorder</Button>
            {o.status === 'DELIVERED' && <Button size="small" variant="outlined" onClick={() => send(`Can I return something from order ${o.order_number}?`)} data-testid="ai-order-return">Return</Button>}
          </Stack>
        </Paper>
      ))}
    </Stack>
  );
};

export const TrackingCard = ({ tracking }: { tracking: AiTracking }) => (
  <Paper variant="outlined" sx={{ p: 1.25 }} data-testid="ai-tracking">
    <Stack direction="row" justifyContent="space-between" alignItems="center">
      <Typography variant="subtitle2">{tracking.order_number}</Typography>
      <StatusChip status={tracking.order_status} />
    </Stack>
    {tracking.shipment ? (
      <Box sx={{ mt: 0.5 }}>
        <Typography variant="body2" component="div">
          Tracking <strong>{tracking.shipment.tracking_number}</strong> · {tracking.shipment.courier} · <StatusChip status={tracking.shipment.status} />
        </Typography>
        <Typography variant="caption" color="text.secondary" display="block">
          {tracking.shipment.estimated_delivery ? `Estimated delivery ${formatDate(tracking.shipment.estimated_delivery)}` : 'No estimated delivery date'}
        </Typography>
        <Divider sx={{ my: 0.75 }} />
        {tracking.shipment.events.map((e) => (
          <Typography key={`${e.status}-${e.time}`} variant="caption" display="block">{formatDate(e.time)} — {e.status.replace(/_/g, ' ')}</Typography>
        ))}
      </Box>
    ) : (
      <Typography variant="body2" sx={{ mt: 0.5 }}>{tracking.message ?? 'No shipment yet.'}</Typography>
    )}
  </Paper>
);

export const CartCard = ({ cart }: { cart: AiCart }) => (
  <Paper variant="outlined" sx={{ p: 1.25 }} data-testid="ai-cart">
    <Typography variant="subtitle2">Your cart</Typography>
    {cart.empty ? <Typography variant="body2">Your cart is empty.</Typography> : (
      <>
        {cart.items.map((i) => (
          <Typography key={i.product_id} variant="body2">{i.quantity} × {i.name} — {formatPrice(i.line_total)}</Typography>
        ))}
        <Typography variant="body2" fontWeight={700}>Total {formatPrice(cart.total_amount)}</Typography>
      </>
    )}
  </Paper>
);

export const PaymentCards = ({ payments }: { payments: AiPayment[] }) => (
  <Stack spacing={0.75} data-testid="ai-payments">
    {payments.map((p) => (
      <Paper key={p.payment_id} variant="outlined" sx={{ p: 1 }}>
        <Stack direction="row" justifyContent="space-between"><Typography variant="subtitle2">{p.reference}</Typography><StatusChip status={p.status} /></Stack>
        <Typography variant="caption" color="text.secondary">{formatPrice(p.amount)} via {p.method}{p.paid_at ? ` · paid ${formatDate(p.paid_at)}` : ''}</Typography>
      </Paper>
    ))}
  </Stack>
);

export const RefundCards = ({ refunds }: { refunds: AiRefundGroup[] }) => (
  <Stack spacing={0.75} data-testid="ai-refunds">
    {refunds.filter((g) => g.refunds.length > 0).map((g) => (
      <Paper key={g.payment_id} variant="outlined" sx={{ p: 1 }}>
        <Typography variant="caption" color="text.secondary">Refunded {formatPrice(g.refunded_amount)} of {formatPrice(g.paid_amount)} · payment {g.payment_status.replace(/_/g, ' ')}</Typography>
        {g.refunds.map((r) => (
          <Stack key={r.refund_id} direction="row" justifyContent="space-between" alignItems="center">
            <Typography variant="body2">{formatPrice(r.amount)}</Typography><StatusChip status={r.status} />
          </Stack>
        ))}
      </Paper>
    ))}
  </Stack>
);

export const ReviewsCard = ({ reviews }: { reviews: AiReviews }) => (
  <Paper variant="outlined" sx={{ p: 1.25 }} data-testid="ai-reviews">
    <Stack direction="row" justifyContent="space-between" alignItems="center">
      <Typography variant="subtitle2">Customer reviews · {reviews.name}</Typography>
      {reviews.total_reviews > 0 && reviews.average_rating != null && (
        <Chip size="small" color="success" variant="outlined" label={`★ ${Number(reviews.average_rating).toFixed(1)} (${reviews.total_reviews})`} />
      )}
    </Stack>
    {reviews.reviews.length === 0 ? (
      <Typography variant="body2" color="text.secondary">No approved reviews yet.</Typography>
    ) : reviews.reviews.map((r, i) => (
      <Box key={i} sx={{ mt: 0.75 }}>
        <Typography variant="body2" fontWeight={600}>{r.rating}/5{r.title ? ` · ${r.title}` : ''}</Typography>
        {r.comment && <Typography variant="caption" color="text.secondary" display="block" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{r.comment}</Typography>}
      </Box>
    ))}
  </Paper>
);

export const EligibilityCard = ({ eligibility }: { eligibility: AiEligibility }) => (
  <Paper variant="outlined" sx={{ p: 1.25 }} data-testid="ai-eligibility">
    <Stack direction="row" justifyContent="space-between" alignItems="center">
      <Typography variant="subtitle2">{eligibility.product_name ?? 'Return check'}{eligibility.order_number ? ` · ${eligibility.order_number}` : ''}</Typography>
      <Chip size="small" color={eligibility.eligible ? 'success' : 'error'} label={eligibility.eligible ? 'Eligible for return' : 'Not eligible'} />
    </Stack>
    <Typography variant="caption" color="text.secondary">{eligibility.message}</Typography>
  </Paper>
);
