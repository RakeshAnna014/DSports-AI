import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Button, Skeleton, Stack, Tab, Tabs, Typography } from '@mui/material';
import { ReceiptLongOutlined } from '@mui/icons-material';
import type { OrderSummaryResponse } from '@/types/orders';
import { ordersApi } from '@/api/orders';
import OrderCard from '@/components/orders/OrderCard';
import { EmptyState, ErrorState } from '@/components/common/States';

const FILTERS = [
  { key: 'all', label: 'All', test: () => true },
  { key: 'active', label: 'In progress', test: (s: string) => ['CREATED', 'PENDING_PAYMENT', 'CONFIRMED', 'PROCESSING', 'SHIPPED'].includes(s) },
  { key: 'delivered', label: 'Delivered', test: (s: string) => s === 'DELIVERED' },
  { key: 'closed', label: 'Cancelled / refunded', test: (s: string) => s === 'CANCELLED' || s === 'REFUNDED' },
];

/** Recent and previous orders as cards with status, products, total and the next actions (view, track, reorder, return). */
const OrdersPage = () => {
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderSummaryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('all');

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setOrders(await ordersApi.getOrders());
    } catch {
      setError("We couldn't load your orders right now.");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const cancel = async (id: string) => {
    try {
      await ordersApi.cancelOrder(id);
      await load();
    } catch {
      setError("That order couldn't be cancelled. Please try again.");
    }
  };

  const active = FILTERS.find((f) => f.key === filter) ?? FILTERS[0];
  const shown = orders.filter((o) => active.test(o.status));

  return (
    <Box>
      <Typography variant="h3" component="h1" sx={{ mb: 0.5 }}>My Orders</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Track, reorder or return in one tap. DSports AI can help with any of them.</Typography>
      <Tabs value={filter} onChange={(_, v) => setFilter(v)} variant="scrollable" allowScrollButtonsMobile sx={{ mb: 2 }} aria-label="Order filter">
        {FILTERS.map((f) => <Tab key={f.key} value={f.key} label={f.label} />)}
      </Tabs>
      {error && <Box sx={{ mb: 2 }}><ErrorState body={error} onRetry={load} /></Box>}
      {loading ? (
        <Stack spacing={1.5} aria-busy="true">{[0, 1, 2].map((i) => <Skeleton key={i} variant="rounded" height={150} />)}</Stack>
      ) : shown.length === 0 && !error ? (
        <EmptyState icon={<ReceiptLongOutlined sx={{ fontSize: 56 }} />} title={orders.length === 0 ? 'No orders yet' : 'No orders in this view'}
          body={orders.length === 0 ? 'Start shopping and your orders will appear here.' : 'Try another filter.'}
          action={orders.length === 0 ? <Button variant="contained" onClick={() => navigate('/products')}>Browse products</Button> : undefined} />
      ) : (
        <Stack spacing={1.5}>{shown.map((o) => <OrderCard key={o.id} order={o} onCancel={cancel} />)}</Stack>
      )}
    </Box>
  );
};

export default OrdersPage;
