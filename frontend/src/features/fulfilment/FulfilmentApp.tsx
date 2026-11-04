import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Skeleton, Stack, Tab, Table, TableBody, TableCell,
  TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { HomeOutlined } from '@mui/icons-material';
import { Link as RouterLink } from 'react-router-dom';
import { fulfilmentApi, type FulfilmentRow } from '@/api/fulfilment';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';

const isAdmin = (roles?: string[]) => !!roles?.some((r) => r === 'ADMIN' || r === 'ROLE_ADMIN');

const VIEWS = [
  { key: 'active', label: 'To fulfil', statuses: ['CONFIRMED', 'PROCESSING', 'SHIPPED'] },
  { key: 'awaiting', label: 'Awaiting payment', statuses: ['PENDING_PAYMENT'] },
  { key: 'delivered', label: 'Delivered', statuses: ['DELIVERED'] },
  { key: 'closed', label: 'Cancelled / refunded', statuses: ['CANCELLED', 'REFUNDED'] },
] as const;

const STEP_LABEL: Record<string, string> = {
  READY_FOR_PICKUP: 'Ready for pickup',
  PICKED_UP: 'Dispatch (picked up)',
  IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Mark delivered',
  FAILED: 'Delivery failed',
  RETURNED: 'Returned to sender',
};
const EXCEPTIONS = new Set(['FAILED', 'RETURNED']);

const label = (s: string) => s.replace(/_/g, ' ').toLowerCase();

/**
 * Internal demo fulfilment console (ADMIN). It only calls the existing admin endpoints: the backend enforces the role, the valid
 * transitions and the order-follows-shipment synchronisation. Payments are never changed here, and nothing is offered for an unpaid order.
 */
const FulfilmentApp = () => {
  const { user, isAuthenticated } = useAuthStore();
  const allowed = isAuthenticated && isAdmin(user?.roles);
  const qc = useQueryClient();
  const [view, setView] = useState<(typeof VIEWS)[number]['key']>('active');
  const [failing, setFailing] = useState<FulfilmentRow | null>(null);
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<{ severity: 'success' | 'error'; text: string } | null>(null);
  const statuses = VIEWS.find((v) => v.key === view)!.statuses as readonly string[];

  const board = useQuery({ queryKey: ['fulfilment', view], queryFn: () => fulfilmentApi.board([...statuses]), enabled: allowed, retry: false });
  const act = useMutation({
    mutationFn: async (a: { row: FulfilmentRow; step: string; reason?: string }) => {
      if (a.step === 'CREATE_SHIPMENT') return fulfilmentApi.createShipment(a.row.orderId);
      return fulfilmentApi.updateShipment(a.row.shipmentId!, a.step, a.reason);
    },
    onSuccess: (_d, a) => {
      setMessage({ severity: 'success', text: `${a.row.orderNumber}: ${a.step === 'CREATE_SHIPMENT' ? 'shipment created' : `shipment ${label(a.step)}`}. The order status follows the shipment.` });
      qc.invalidateQueries({ queryKey: ['fulfilment'] });
    },
    onError: (e, a) => setMessage({ severity: 'error', text: `${a.row.orderNumber}: ${getApiErrorMessage(e, 'The step could not be completed.')}` }),
  });

  if (!allowed) {
    return (
      <Container maxWidth="sm" sx={{ py: 6, textAlign: 'center' }} data-testid="ful-restricted">
        <Typography variant="h5" fontWeight={900} gutterBottom>Fulfilment console</Typography>
        <Alert severity="warning" sx={{ mb: 2 }}>The fulfilment console is restricted to authorised demo operators.</Alert>
        <Button component={RouterLink} to={isAuthenticated ? '/' : '/login'}>{isAuthenticated ? 'Back to the shop' : 'Sign in'}</Button>
      </Container>
    );
  }

  const rows = board.data ?? [];
  return (
    <Container maxWidth="lg" sx={{ py: 3 }} data-testid="ful-app">
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
        <Box>
          <Typography variant="overline" color="text.secondary" fontWeight={800}>INTERNAL · DEMO FULFILMENT</Typography>
          <Typography variant="h5" fontWeight={900}>Fulfilment console</Typography>
        </Box>
        <Button component={RouterLink} to="/" variant="outlined" size="small" startIcon={<HomeOutlined />} aria-label="Home">Home</Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ my: 1.5 }}>
        Moves paid orders through dispatch and delivery using the real backend rules, so Return AI can be tried on a real delivered order. An order only advances if its
        payment was captured; payments are not changed here. Demo payments and refunds are simulated, not real money.
      </Typography>
      <Tabs value={view} onChange={(_e, v) => { setView(v); setMessage(null); }} sx={{ mb: 1 }} variant="scrollable" scrollButtons="auto">
        {VIEWS.map((v) => <Tab key={v.key} value={v.key} label={v.label} data-testid={`ful-tab-${v.key}`} />)}
      </Tabs>
      {message && <Alert severity={message.severity} sx={{ mb: 1 }} onClose={() => setMessage(null)} data-testid="ful-message">{message.text}</Alert>}
      {board.isLoading && <Skeleton variant="rounded" height={160} data-testid="ful-loading" />}
      {board.isError && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => board.refetch()}>Retry</Button>} data-testid="ful-error">
          {getApiErrorMessage(board.error, 'The orders could not be loaded.')}
        </Alert>
      )}
      {board.data && rows.length === 0 && <Alert severity="info" data-testid="ful-empty">No orders in this view. Place an order and pay for it first.</Alert>}
      {rows.length > 0 && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small" data-testid="ful-table">
            <TableHead>
              <TableRow><TableCell>Order</TableCell><TableCell>Customer</TableCell><TableCell>Payment</TableCell><TableCell>Order status</TableCell><TableCell>Shipment</TableCell><TableCell>Next step</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.orderId} data-testid={`ful-row-${r.orderNumber}`}>
                  <TableCell><Typography variant="body2" fontWeight={700}>{r.orderNumber}</Typography><Typography variant="caption" color="text.secondary">{r.items} item(s) · {r.currency} {r.grandTotal}</Typography></TableCell>
                  <TableCell>{r.customer ?? '—'}</TableCell>
                  <TableCell><Chip size="small" color={r.paid ? 'success' : 'default'} label={r.paymentStatus ? label(r.paymentStatus) : 'no payment'} /></TableCell>
                  <TableCell><Chip size="small" label={label(r.orderStatus)} /></TableCell>
                  <TableCell>
                    {r.shipmentStatus ? (<><Chip size="small" color={r.shipmentStatus === 'DELIVERED' ? 'success' : 'info'} label={label(r.shipmentStatus)} /><Typography variant="caption" display="block" color="text.secondary">{r.trackingNumber ?? r.shipmentNumber}</Typography></>) : '—'}
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                      {r.canCreateShipment && <Button size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ row: r, step: 'CREATE_SHIPMENT' })}>Create shipment</Button>}
                      {r.nextShipmentStatuses.filter((s) => !EXCEPTIONS.has(s)).map((s) => (
                        <Button key={s} size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ row: r, step: s })}>{STEP_LABEL[s] ?? label(s)}</Button>
                      ))}
                      {r.nextShipmentStatuses.filter((s) => EXCEPTIONS.has(s)).map((s) => (
                        <Button key={s} size="small" variant="outlined" color="warning" disabled={act.isPending}
                          onClick={() => (s === 'FAILED' ? (setFailing(r), setReason('')) : act.mutate({ row: r, step: s, reason: 'Returned to sender (demo)' }))}>{STEP_LABEL[s] ?? label(s)}</Button>
                      ))}
                      {!r.paid && r.orderStatus === 'PENDING_PAYMENT' && <Typography variant="caption" color="text.secondary">Waiting for payment: nothing to do here.</Typography>}
                      {r.paid && !r.canCreateShipment && r.nextShipmentStatuses.length === 0 && <Typography variant="caption" color="text.secondary">{r.shipmentStatus === 'DELIVERED' ? 'Delivered: ready for Return AI.' : 'No step available.'}</Typography>}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      <Dialog open={!!failing} onClose={() => setFailing(null)} fullWidth maxWidth="xs">
        <DialogTitle>Record a delivery failure</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>{failing?.orderNumber}: the shipment is marked failed and can be retried (ready for pickup) afterwards.</Typography>
          <TextField fullWidth size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 500 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setFailing(null)}>Cancel</Button>
          <Button variant="contained" color="warning" disabled={!reason.trim() || act.isPending}
            onClick={() => { act.mutate({ row: failing!, step: 'FAILED', reason: reason.trim() }); setFailing(null); }}>Record failure</Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
};

export default FulfilmentApp;
