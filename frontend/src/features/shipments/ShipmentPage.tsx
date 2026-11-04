import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Box,
  Typography,
  Paper,
  Card,
  CardContent,
  Stepper,
  Step,
  StepLabel,
  CircularProgress,
  Alert,
  Chip,
  type ChipProps,
} from '@mui/material';
import LocalShippingIcon from '@mui/icons-material/LocalShipping';
import { shipmentsApi, ShipmentResult } from '@/api/shipments';

const statusSteps = ['CREATED', 'READY_FOR_PICKUP', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'];
const statusColors: Record<string, ChipProps['color']> = {
  CREATED: 'default',
  READY_FOR_PICKUP: 'info',
  PICKED_UP: 'primary',
  IN_TRANSIT: 'warning',
  OUT_FOR_DELIVERY: 'info',
  DELIVERED: 'success',
  FAILED: 'error',
  RETURNED: 'secondary',
};

const activeStepIndex = (status: string) => {
  const idx = statusSteps.indexOf(status);
  return idx >= 0 ? idx : -1;
};

const ShipmentPage = () => {
  const { id } = useParams<{ id: string }>();
  const [shipment, setShipment] = useState<ShipmentResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    shipmentsApi
      .getById(id)
      .then(setShipment)
      .catch(() => setError('Shipment not found'))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>;
  if (error) return <Box sx={{ maxWidth: 800, mx: 'auto', py: 4, px: 2 }}><Alert severity="error">{error}</Alert></Box>;
  if (!shipment) return null;

  return (
    <Box sx={{ maxWidth: 800, mx: 'auto', py: 4, px: 2 }}>
      <Typography variant="h4" gutterBottom>
        <LocalShippingIcon sx={{ mr: 1, verticalAlign: 'middle' }} />
        Shipment {shipment.shipmentNumber}
      </Typography>

      <Box sx={{ mb: 2 }}>
        <Chip
          label={shipment.status.replace(/_/g, ' ')}
          color={statusColors[shipment.status] || 'default'}
          sx={{ fontWeight: 600 }}
        />
      </Box>

      <Card sx={{ mb: 4 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>Shipping Details</Typography>
          <Typography><strong>Tracking Number:</strong> {shipment.trackingNumber || 'Not assigned'}</Typography>
          <Typography><strong>Courier:</strong> {shipment.courierProvider || 'Not assigned'}</Typography>
          <Typography><strong>Address:</strong> {shipment.street}, {shipment.city}, {shipment.state} {shipment.zipCode}, {shipment.country}</Typography>
          {shipment.estimatedDeliveryDate && (
            <Typography><strong>Estimated Delivery:</strong> {new Date(shipment.estimatedDeliveryDate).toLocaleDateString()}</Typography>
          )}
          {shipment.shippedAt && (
            <Typography><strong>Shipped At:</strong> {new Date(shipment.shippedAt).toLocaleString()}</Typography>
          )}
          {shipment.deliveredAt && (
            <Typography><strong>Delivered At:</strong> {new Date(shipment.deliveredAt).toLocaleString()}</Typography>
          )}
        </CardContent>
      </Card>

      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" gutterBottom>Shipment Timeline</Typography>
        <Stepper activeStep={activeStepIndex(shipment.status)} orientation="vertical">
          {statusSteps.map((step, index) => (
            <Step key={step} completed={index <= activeStepIndex(shipment.status) || shipment.status === 'DELIVERED' || shipment.status === 'FAILED' || shipment.status === 'RETURNED'}>
              <StepLabel>{step.replace(/_/g, ' ')}</StepLabel>
            </Step>
          ))}
        </Stepper>
      </Paper>
    </Box>
  );
};

export default ShipmentPage;
