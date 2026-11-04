import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { ordersApi } from '@/api/orders';
import { useAiStore } from '@/store/aiStore';
import { formatDate, formatPrice } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { colors, motion, shadows } from '@/design/tokens';
import type { OrderSummaryResponse } from '@/types/orders';

const STATUS_COLORS: Record<string, 'default' | 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'info'> = {
  CREATED: 'default', PENDING_PAYMENT: 'warning', CONFIRMED: 'info', PROCESSING: 'primary', SHIPPED: 'secondary', DELIVERED: 'success', CANCELLED: 'error', REFUNDED: 'default',
};

/** One order: status, products, total, and the next actions. Track / Reorder / Return are handled by the AI so they use the same safe flows. */
const OrderCard = ({ order, onCancel }: { order: OrderSummaryResponse; onCancel: (id: string) => void }) => {
  const navigate = useNavigate();
  const send = useAiStore((s) => s.send);
  const { data: detail, isLoading } = useQuery({ queryKey: ['order', order.id], queryFn: () => ordersApi.getOrder(order.id), staleTime: 60_000 });
  const askAi = (message: string) => { void send(message); navigate('/ai'); };
  const cancellable = order.status === 'CREATED' || order.status === 'PENDING_PAYMENT';

  return (
    <Paper variant="outlined" data-testid="order-card" sx={{ p: 2, borderColor: colors.line, transition: `box-shadow ${motion.base}`, '&:hover': { boxShadow: shadows.card } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1} sx={{ mb: 1.25 }}>
        <Box>
          <Typography variant="subtitle1" fontWeight={800}>Order #{order.orderNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{formatDate(order.placedAt)} · {order.totalItems} item(s)</Typography>
        </Box>
        <Stack alignItems="flex-end" spacing={0.5}>
          <Chip size="small" color={STATUS_COLORS[order.status] ?? 'default'} label={order.status.replace(/_/g, ' ')} />
          <Typography variant="subtitle1" fontWeight={800} color="primary.dark">{formatPrice(order.grandTotal, order.currency)}</Typography>
        </Stack>
      </Stack>
      <Stack spacing={0.75} sx={{ mb: 1.5 }}>
        {isLoading ? <Skeleton width="70%" /> : detail?.items.map((i) => (
          <Stack key={i.id} direction="row" spacing={1.25} alignItems="center">
            <ProductImage src={i.productImage} productId={i.productId} alt={i.productName} sx={{ width: 52, flexShrink: 0, borderRadius: 2 }} />
            <Typography variant="body2" sx={{ flexGrow: 1 }}>{i.quantity} × {i.productName}</Typography>
            <Typography variant="body2" color="text.secondary">{formatPrice(i.unitPrice, order.currency)}</Typography>
          </Stack>
        ))}
      </Stack>
      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
        <Button size="small" variant="contained" onClick={() => navigate(`/orders/${order.id}`)}>View order</Button>
        <Button size="small" variant="outlined" onClick={() => askAi(`Where is order ${order.orderNumber}?`)} data-testid="order-track">Track</Button>
        <Button size="small" variant="outlined" onClick={() => askAi(`Reorder from order ${order.orderNumber}`)} data-testid="order-reorder">Reorder</Button>
        {order.status === 'DELIVERED' && <Button size="small" variant="outlined" onClick={() => askAi(`Can I return something from order ${order.orderNumber}?`)} data-testid="order-return">Return</Button>}
        {cancellable && <Button size="small" color="error" onClick={() => onCancel(order.id)}>Cancel order</Button>}
      </Stack>
    </Paper>
  );
};

export default OrderCard;
