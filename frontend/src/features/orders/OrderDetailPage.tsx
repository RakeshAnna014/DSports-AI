import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box,
  Typography,
  Button,
  Card,
  CardContent,
  Divider,
  Stack,
  Alert,
  CircularProgress,
  Chip,
  Stepper,
  Step,
  StepLabel,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  ArrowBack,
  LocalShippingOutlined,
  PaymentOutlined,
  CheckCircleOutline,
  CancelOutlined,
  Inventory2Outlined,
} from '@mui/icons-material';
import type { OrderResponse } from '@/types/orders';
import { ordersApi } from '@/api/orders';
import { formatPrice, formatDate } from '@/lib/productUtils';

const ORDER_STEPS = [
  { label: 'Placed', statuses: ['CREATED', 'PENDING_PAYMENT'] },
  { label: 'Confirmed', statuses: ['CONFIRMED'] },
  { label: 'Processing', statuses: ['PROCESSING'] },
  { label: 'Shipped', statuses: ['SHIPPED'] },
  { label: 'Delivered', statuses: ['DELIVERED'] },
];

const STATUS_COLORS: Record<string, 'default' | 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'info'> = {
  CREATED: 'default',
  PENDING_PAYMENT: 'warning',
  CONFIRMED: 'info',
  PROCESSING: 'primary',
  SHIPPED: 'secondary',
  DELIVERED: 'success',
  CANCELLED: 'error',
  REFUNDED: 'default',
};

const OrderDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  useEffect(() => {
    if (!id) return;
    loadOrder();
  }, [id]);

  const loadOrder = async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await ordersApi.getOrder(id);
      setOrder(data);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Failed to load order');
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = async () => {
    if (!id) return;
    setCancelling(true);
    try {
      const updated = await ordersApi.cancelOrder(id);
      setOrder(updated);
      setCancelDialogOpen(false);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Failed to cancel order');
    } finally {
      setCancelling(false);
    }
  };

  const getActiveStep = (status: string) => {
    if (status === 'CANCELLED' || status === 'REFUNDED') return -1;
    const idx = ORDER_STEPS.findIndex((step) => step.statuses.includes(status));
    return idx >= 0 ? idx : 0;
  };

  const canCancel = order && ['CREATED', 'PENDING_PAYMENT', 'CONFIRMED', 'PROCESSING'].includes(order.status);

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error && !order) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>
        <Button variant="contained" onClick={() => navigate('/orders')}>
          Back to Orders
        </Button>
      </Box>
    );
  }

  if (!order) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  const activeStep = getActiveStep(order.status);
  const isCancelled = order.status === 'CANCELLED' || order.status === 'REFUNDED';

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Button startIcon={<ArrowBack />} onClick={() => navigate('/orders')} size="small">
          Back to Orders
        </Button>
      </Box>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Box>
          <Typography variant="h4" fontWeight={700}>
            Order {order.orderNumber}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Placed on {formatDate(order.placedAt)}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
          <Chip
            label={order.status.replace('_', ' ')}
            color={STATUS_COLORS[order.status] || 'default'}
            size="medium"
          />
          {canCancel && (
            <Button
              variant="outlined"
              color="error"
              startIcon={<CancelOutlined />}
              onClick={() => setCancelDialogOpen(true)}
              size="small"
            >
              Cancel Order
            </Button>
          )}
        </Box>
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {isCancelled ? (
        <Alert severity="warning" icon={<CancelOutlined />} sx={{ mb: 3 }}>
          This order has been {order.status.toLowerCase()}.
        </Alert>
      ) : (
        <Card variant="outlined" sx={{ borderRadius: 2, mb: 3 }}>
          <CardContent>
            <Typography variant="h6" fontWeight={600} gutterBottom>
              <LocalShippingOutlined sx={{ mr: 1, verticalAlign: 'middle' }} />
              Order Progress
            </Typography>
            <Stepper activeStep={activeStep} alternativeLabel sx={{ mt: 2 }}>
              {ORDER_STEPS.map((step) => (
                <Step key={step.label}>
                  <StepLabel>{step.label}</StepLabel>
                </Step>
              ))}
            </Stepper>
          </CardContent>
        </Card>
      )}

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Stack spacing={2}>
            <Card variant="outlined" sx={{ borderRadius: 2 }}>
              <CardContent>
                <Typography variant="h6" fontWeight={600} gutterBottom>
                  <Inventory2Outlined sx={{ mr: 1, verticalAlign: 'middle' }} />
                  Items ({order.items.length})
                </Typography>
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell><strong>Product</strong></TableCell>
                        <TableCell><strong>SKU</strong></TableCell>
                        <TableCell align="right"><strong>Qty</strong></TableCell>
                        <TableCell align="right"><strong>Unit Price</strong></TableCell>
                        <TableCell align="right"><strong>Total</strong></TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {order.items.map((item) => (
                        <TableRow key={item.id}>
                          <TableCell>
                            <Typography variant="body2" fontWeight={500}>
                              {item.productName}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption" color="text.secondary">
                              {item.sku}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">{item.quantity}</TableCell>
                          <TableCell align="right">{formatPrice(item.unitPrice)}</TableCell>
                          <TableCell align="right" fontWeight={600}>
                            {formatPrice(item.lineTotal)}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </CardContent>
            </Card>

            <Card variant="outlined" sx={{ borderRadius: 2 }}>
              <CardContent>
                <Typography variant="h6" fontWeight={600} gutterBottom>
                  Shipping Address
                </Typography>
                <Typography variant="body2">
                  {order.shippingAddress.fullName}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {order.shippingAddress.line1}
                  {order.shippingAddress.line2 ? `, ${order.shippingAddress.line2}` : ''}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {order.shippingAddress.city}, {order.shippingAddress.state} {order.shippingAddress.postalCode}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {order.shippingAddress.country}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Phone: {order.shippingAddress.phone}
                </Typography>
              </CardContent>
            </Card>
          </Stack>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Card variant="outlined" sx={{ borderRadius: 2, position: 'sticky', top: 88 }}>
            <CardContent>
              <Typography variant="h6" fontWeight={600} gutterBottom>
                Order Summary
              </Typography>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Subtotal</Typography>
                <Typography variant="body2">{formatPrice(order.subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Shipping</Typography>
                <Typography variant="body2">{formatPrice(order.shippingCharge)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Tax</Typography>
                <Typography variant="body2">{formatPrice(order.taxAmount)}</Typography>
              </Box>
              {Number(order.discountAmount) > 0 && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography variant="body2" color="success.main">Discount</Typography>
                  <Typography variant="body2" color="success.main">-{formatPrice(order.discountAmount)}</Typography>
                </Box>
              )}
              <Divider sx={{ my: 1.5 }} />
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="subtitle1" fontWeight={700}>Grand Total</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="primary.main">
                  {formatPrice(order.grandTotal)}
                </Typography>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Dialog open={cancelDialogOpen} onClose={() => setCancelDialogOpen(false)}>
        <DialogTitle>Cancel Order</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Are you sure you want to cancel order {order.orderNumber}? This action cannot be undone.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCancelDialogOpen(false)} disabled={cancelling}>
            Keep Order
          </Button>
          <Button onClick={handleCancel} color="error" variant="contained" disabled={cancelling}>
            {cancelling ? <CircularProgress size={20} /> : 'Cancel Order'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default OrderDetailPage;
