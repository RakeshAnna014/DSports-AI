import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material';
import { CheckCircle, RadioButtonUnchecked } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { formatDate } from '@/lib/productUtils';
import { colors, gradients } from '@/design/tokens';
import { StatusChip } from '@/features/ai/OrderStatusCard';
import type { AiTracking } from '@/types/ai';

interface Step { key: string; label: string; done: boolean; time?: string | null }

const ORDER_CONFIRMED = new Set(['CONFIRMED', 'PROCESSING', 'SHIPPED', 'DELIVERED']);
const RANK: Record<string, number> = { CREATED: 0, READY_FOR_PICKUP: 1, PICKED_UP: 2, IN_TRANSIT: 3, OUT_FOR_DELIVERY: 4, DELIVERED: 5 };

/** The ladder is derived ONLY from the real order status and shipment events; a step is "done" when DSports says it happened. */
export const buildSteps = (t: AiTracking): Step[] => {
  const events = t.shipment?.events ?? [];
  const reached = Math.max(-1, ...events.map((e) => RANK[e.status] ?? -1), t.shipment ? RANK[t.shipment.status] ?? -1 : -1);
  const at = (status: string) => events.find((e) => e.status === status)?.time ?? null;
  return [
    { key: 'placed', label: 'Order placed', done: true },
    { key: 'confirmed', label: 'Confirmed', done: ORDER_CONFIRMED.has(t.order_status) },
    { key: 'packed', label: 'Packed', done: reached >= RANK.READY_FOR_PICKUP || ['PROCESSING', 'SHIPPED', 'DELIVERED'].includes(t.order_status), time: at('READY_FOR_PICKUP') ?? at('CREATED') },
    { key: 'transit', label: 'In transit', done: reached >= RANK.PICKED_UP, time: at('IN_TRANSIT') ?? at('PICKED_UP') },
    { key: 'out', label: 'Out for delivery', done: reached >= RANK.OUT_FOR_DELIVERY, time: at('OUT_FOR_DELIVERY') },
    { key: 'delivered', label: 'Delivered', done: reached >= RANK.DELIVERED || t.order_status === 'DELIVERED', time: t.shipment?.delivered_at ?? at('DELIVERED') },
  ];
};

const OrderTimeline = ({ tracking, onNavigate }: { tracking: AiTracking; onNavigate: () => void }) => {
  const navigate = useNavigate();
  const steps = buildSteps(tracking);
  const current = steps.findIndex((s) => !s.done);
  const s = tracking.shipment;
  return (
    <Paper variant="outlined" sx={{ p: 2, overflow: 'hidden' }} data-testid="ai-tracking">
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }} spacing={1}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ letterSpacing: '.03em' }}>ORDER #{tracking.order_number}</Typography>
        <StatusChip status={tracking.order_status} />
      </Stack>
      <Box component="ol" aria-label="Order progress" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {steps.map((step, i) => {
          const active = i === current;
          return (
            <Box component="li" key={step.key} data-testid={`timeline-${step.key}`} data-done={step.done} aria-current={active ? 'step' : undefined}
              sx={{ display: 'flex', gap: 1.5, position: 'relative', pb: i === steps.length - 1 ? 0 : 1.75 }}>
              {i < steps.length - 1 && <Box sx={{ position: 'absolute', left: 11, top: 24, bottom: 0, width: 2, background: step.done && steps[i + 1].done ? gradients.brand : colors.line }} />}
              {step.done
                ? <CheckCircle sx={{ color: 'success.main', zIndex: 1, bgcolor: 'background.paper', borderRadius: '50%' }} />
                : active
                  ? <Box sx={{ width: 24, height: 24, display: 'grid', placeItems: 'center', zIndex: 1 }}><Box sx={{ width: 14, height: 14, borderRadius: '50%', background: gradients.ai, boxShadow: '0 0 0 4px rgba(255,122,26,0.22)' }} /></Box>
                  : <RadioButtonUnchecked sx={{ color: 'text.disabled', zIndex: 1, bgcolor: 'background.paper', borderRadius: '50%' }} />}
              <Box>
                <Typography variant="body2" fontWeight={step.done || active ? 700 : 500} color={step.done || active ? 'text.primary' : 'text.disabled'}>{step.label}</Typography>
                {step.time && <Typography variant="caption" color="text.secondary">{formatDate(step.time)}</Typography>}
              </Box>
            </Box>
          );
        })}
      </Box>
      {s ? (
        <Stack spacing={0.25} sx={{ mt: 1.5, pt: 1.25, borderTop: `1px solid ${colors.line}` }}>
          <Typography variant="caption" color="text.secondary">Tracking <strong>{s.tracking_number}</strong> · {s.courier}</Typography>
          <Typography variant="caption" color="text.secondary">
            {s.status === 'DELIVERED' && s.delivered_at ? `Delivered ${formatDate(s.delivered_at)}`
              : s.estimated_delivery ? `Expected delivery: ${formatDate(s.estimated_delivery)}` : 'No estimated delivery date from the carrier yet'}
          </Typography>
        </Stack>
      ) : (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>{tracking.message ?? 'No shipment has been created for this order yet.'}</Typography>
      )}
      {s && ['FAILED', 'RETURNED'].includes(s.status) && <Alert severity="warning" sx={{ mt: 1.5 }}>Delivery problem: the shipment is {s.status.toLowerCase()}.</Alert>}
      <Button size="small" sx={{ mt: 1 }} onClick={() => { onNavigate(); navigate(`/orders/${tracking.order_id}`); }}>View order</Button>
    </Paper>
  );
};

export default OrderTimeline;
