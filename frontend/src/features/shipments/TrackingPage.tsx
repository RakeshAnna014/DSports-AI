import { useState } from 'react';
import {
  Box,
  TextField,
  Button,
  Typography,
  Paper,
  Card,
  CardContent,
  Stepper,
  Step,
  StepLabel,
  CircularProgress,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import LocalShippingIcon from '@mui/icons-material/LocalShipping';
import { shipmentsApi, ShipmentResult } from '@/api/shipments';

const statusSteps = ['CREATED', 'READY_FOR_PICKUP', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'];
const activeStepIndex = (status: string) => statusSteps.indexOf(status);

const TrackingPage = () => {
  const [trackingNumber, setTrackingNumber] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shipment, setShipment] = useState<ShipmentResult | null>(null);

  const handleTrack = async () => {
    if (!trackingNumber.trim()) return;
    setLoading(true);
    setError(null);
    setShipment(null);
    try {
      const result = await shipmentsApi.trackByNumber(trackingNumber.trim());
      setShipment(result);
    } catch {
      setError('Shipment not found for tracking number: ' + trackingNumber);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ maxWidth: 800, mx: 'auto', py: 4, px: 2 }}>
      <Typography variant="h4" gutterBottom>
        <LocalShippingIcon sx={{ mr: 1, verticalAlign: 'middle' }} />
        Track Shipment
      </Typography>

      <Paper sx={{ p: 3, mb: 4 }}>
        <Box sx={{ display: 'flex', gap: 2 }}>
          <TextField
            fullWidth
            label="Tracking Number"
            value={trackingNumber}
            onChange={(e) => setTrackingNumber(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleTrack()}
            placeholder="Enter tracking number..."
          />
          <Button
            variant="contained"
            onClick={handleTrack}
            disabled={loading}
            sx={{ minWidth: 120 }}
          >
            {loading ? <CircularProgress size={24} /> : <><SearchIcon sx={{ mr: 1 }} />Track</>}
          </Button>
        </Box>
      </Paper>

      {error && (
        <Paper sx={{ p: 3, bgcolor: '#fff3f3' }}>
          <Typography color="error">{error}</Typography>
        </Paper>
      )}

      {shipment && (
        <>
          <Card sx={{ mb: 4 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Shipment Details</Typography>
              <Typography><strong>Shipment Number:</strong> {shipment.shipmentNumber}</Typography>
              <Typography><strong>Tracking Number:</strong> {shipment.trackingNumber || 'N/A'}</Typography>
              <Typography><strong>Status:</strong> {shipment.status}</Typography>
              <Typography><strong>Courier:</strong> {shipment.courierProvider || 'Not assigned'}</Typography>
              <Typography><strong>Shipping Address:</strong> {shipment.street}, {shipment.city}, {shipment.state} {shipment.zipCode}, {shipment.country}</Typography>
              {shipment.estimatedDeliveryDate && (
                <Typography><strong>Estimated Delivery:</strong> {new Date(shipment.estimatedDeliveryDate).toLocaleDateString()}</Typography>
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
                <Step key={step} completed={index <= activeStepIndex(shipment.status)}>
                  <StepLabel>{step.replace(/_/g, ' ')}</StepLabel>
                </Step>
              ))}
            </Stepper>
          </Paper>
        </>
      )}
    </Box>
  );
};

export default TrackingPage;
