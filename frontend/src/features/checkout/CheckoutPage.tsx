import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
  Radio,
  RadioGroup,
  FormControlLabel,
  FormControl,
  Chip,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  ListItemIcon,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  ShoppingBagOutlined,
  ArrowBack,
  HomeOutlined,
  CheckCircleOutline,
  LocalShippingOutlined,
} from '@mui/icons-material';
import type { CheckoutResponse } from '@/types/checkout';
import { checkoutApi } from '@/api/checkout';
import { formatPrice } from '@/lib/productUtils';
import apiClient from '@/api/client';

interface Address {
  addressId: string;
  type: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  country: string;
  postalCode: string;
  isDefault: boolean;
}

const DELIVERY_OPTIONS = [
  { code: 'STANDARD', label: 'Standard Delivery', charge: 5, days: 5 },
  { code: 'EXPRESS', label: 'Express Delivery', charge: 15, days: 2 },
  { code: 'NEXT_DAY', label: 'Next Day Delivery', charge: 25, days: 1 },
] as const;

const CheckoutPage = () => {
  const navigate = useNavigate();
  const [checkout, setCheckout] = useState<CheckoutResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [selectedAddress, setSelectedAddress] = useState<string | null>(null);
  const [selectedDelivery, setSelectedDelivery] = useState<string>('STANDARD');
  const [submitting, setSubmitting] = useState(false);
  const [validated, setValidated] = useState(false);
  const initializingRef = useRef(false);

  useEffect(() => {
    if (initializingRef.current) return;
    initializingRef.current = true;
    initCheckout();
  }, []);

  const initCheckout = async () => {
    setLoading(true);
    setError(null);
    try {
      let active: CheckoutResponse;
      try {
        active = await checkoutApi.getActive();
        if (active && (!active.items || active.items.length === 0)) {
          await checkoutApi.cancel(active.id);
          throw new Error('Stale checkout - recreating');
        }
      } catch {
        const created = await checkoutApi.create();
        console.log('Checkout created:', created);
        if (!created?.id) {
          throw new Error('Invalid checkout creation response - missing id');
        }
        active = await checkoutApi.getById(created.id);
        console.log('Checkout fetched:', active);
      }
      setCheckout(active);
      if (active.shippingAddressId) setSelectedAddress(active.shippingAddressId);
      if (active.deliveryMethodCode) setSelectedDelivery(active.deliveryMethodCode);
      if (active.status === 'VALIDATED' || active.status === 'READY_FOR_PAYMENT') setValidated(true);

      const addrResp = await apiClient.get<{ addresses: Address[] }>('/customers/me/addresses');
      setAddresses(addrResp.data.addresses);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || 'Failed to initialize checkout';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  const handleSelectAddress = async (addressId: string) => {
    if (!checkout) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await checkoutApi.selectAddress(checkout.id, { addressId });
      setCheckout(updated);
      setSelectedAddress(addressId);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Failed to select address');
    } finally {
      setSubmitting(false);
    }
  };

  const handleSelectDelivery = async (code: string) => {
    if (!checkout) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await checkoutApi.selectDeliveryMethod(checkout.id, {
        deliveryMethodCode: code as any,
      });
      setCheckout(updated);
      setSelectedDelivery(code);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Failed to select delivery method');
    } finally {
      setSubmitting(false);
    }
  };

  const handleValidate = async () => {
    if (!checkout) return;
    setSubmitting(true);
    setError(null);
    try {
      const validated = await checkoutApi.validate(checkout.id);
      setCheckout(validated);
      setValidated(true);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Validation failed');
    } finally {
      setSubmitting(false);
    }
  };

  const handleContinueToPayment = () => {
    if (!checkout) return;
    navigate(`/checkout/${checkout.id}/payment`);
  };

  const canValidate = checkout && selectedAddress && selectedDelivery && !validated;
  const canProceed = validated && checkout?.status === 'VALIDATED' || checkout?.status === 'READY_FOR_PAYMENT';

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error && !checkout) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>
        <Button variant="contained" onClick={() => navigate('/cart')}>
          Back to Cart
        </Button>
      </Box>
    );
  }

  if (!checkout || !checkout.items) {
    console.error('Invalid checkout response:', checkout);
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (checkout.items.length === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 8 }}>
        <ShoppingBagOutlined sx={{ fontSize: 64, color: 'text.disabled', mb: 2 }} />
        <Typography variant="h5" gutterBottom>Nothing to checkout</Typography>
        <Button variant="contained" onClick={() => navigate('/products')}>
          Browse Products
        </Button>
      </Box>
    );
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Button startIcon={<ArrowBack />} onClick={() => navigate('/cart')} size="small">
          Back to Cart
        </Button>
      </Box>

      <Typography variant="h4" fontWeight={700} gutterBottom>
        Checkout
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {validated && (
        <Alert severity="success" icon={<CheckCircleOutline />} sx={{ mb: 2 }}>
          Checkout validated successfully. Ready for payment.
        </Alert>
      )}

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Stack spacing={2}>
            <Card variant="outlined" sx={{ borderRadius: 2 }}>
              <CardContent>
                <Typography variant="h6" fontWeight={600} gutterBottom>
                  Shipping Address
                </Typography>
                {addresses.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    No saved addresses. Please add an address in your profile.
                  </Typography>
                ) : (
                  <List disablePadding>
                    {addresses.map((addr) => (
                      <ListItem key={addr.addressId} disablePadding sx={{ mb: 1 }}>
                        <ListItemButton
                          selected={selectedAddress === addr.addressId}
                          onClick={() => handleSelectAddress(addr.addressId)}
                          sx={{ borderRadius: 1, border: '1px solid', borderColor: selectedAddress === addr.addressId ? 'primary.main' : 'divider' }}
                        >
                          <ListItemIcon sx={{ minWidth: 40 }}>
                            <HomeOutlined color={selectedAddress === addr.addressId ? 'primary' : 'inherit'} />
                          </ListItemIcon>
                          <ListItemText
                            primary={`${addr.line1}${addr.line2 ? ', ' + addr.line2 : ''}`}
                            secondary={`${addr.city}, ${addr.state} ${addr.postalCode}`}
                            primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
                            secondaryTypographyProps={{ variant: 'caption' }}
                          />
                          {addr.isDefault && (
                            <Chip label="Default" size="small" color="primary" variant="outlined" sx={{ ml: 1 }} />
                          )}
                        </ListItemButton>
                      </ListItem>
                    ))}
                  </List>
                )}
              </CardContent>
            </Card>

            <Card variant="outlined" sx={{ borderRadius: 2 }}>
              <CardContent>
                <Typography variant="h6" fontWeight={600} gutterBottom>
                  <LocalShippingOutlined sx={{ mr: 1, verticalAlign: 'middle' }} />
                  Delivery Method
                </Typography>
                <FormControl>
                  <RadioGroup value={selectedDelivery} onChange={(e) => handleSelectDelivery(e.target.value)}>
                    {DELIVERY_OPTIONS.map((opt) => (
                      <FormControlLabel
                        key={opt.code}
                        value={opt.code}
                        control={<Radio />}
                        label={
                          <Box sx={{ display: 'flex', justifyContent: 'space-between', width: '100%', minWidth: 300 }}>
                            <Typography variant="body2">
                              <strong>{opt.label}</strong> ({opt.days} business days)
                            </Typography>
                            <Typography variant="body2" fontWeight={600}>
                              {formatPrice(opt.charge)}
                            </Typography>
                          </Box>
                        }
                        sx={{ mb: 0.5 }}
                      />
                    ))}
                  </RadioGroup>
                </FormControl>
              </CardContent>
            </Card>

            <Card variant="outlined" sx={{ borderRadius: 2 }}>
              <CardContent>
                <Typography variant="h6" fontWeight={600} gutterBottom>
                  Items ({checkout!.items.length})
                </Typography>
                {checkout!.items.map((item) => (
                  <Box key={item.id} sx={{ display: 'flex', justifyContent: 'space-between', py: 1 }}>
                    <Box>
                      <Typography variant="body2" fontWeight={500}>{item.productName}</Typography>
                      <Typography variant="caption" color="text.secondary">Qty: {item.quantity} x {formatPrice(item.unitPrice)}</Typography>
                    </Box>
                    <Typography variant="body2" fontWeight={600}>{formatPrice(item.lineTotal)}</Typography>
                  </Box>
                ))}
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
                <Typography variant="body2">{formatPrice(checkout!.subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Shipping</Typography>
                <Typography variant="body2">{formatPrice(checkout!.deliveryCharge)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Tax (18%)</Typography>
                <Typography variant="body2">{formatPrice(checkout!.taxAmount)}</Typography>
              </Box>
              {Number(checkout!.discountAmount) > 0 && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography variant="body2" color="success.main">Discount</Typography>
                  <Typography variant="body2" color="success.main">-{formatPrice(checkout!.discountAmount)}</Typography>
                </Box>
              )}
              <Divider sx={{ my: 1.5 }} />
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="subtitle1" fontWeight={700}>Grand Total</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="primary.main">
                  {formatPrice(checkout!.totalAmount)}
                </Typography>
              </Box>

              <Chip
                label={`Status: ${checkout!.status.replace('_', ' ')}`}
                color={validated ? 'success' : 'default'}
                size="small"
                sx={{ mb: 2, width: '100%' }}
              />

              <Stack spacing={1}>
                {!validated ? (
                  <Button
                    variant="contained"
                    size="large"
                    fullWidth
                    onClick={handleValidate}
                    disabled={!canValidate || submitting}
                  >
                    {submitting ? <CircularProgress size={24} /> : 'Validate Checkout'}
                  </Button>
                ) : (
                  <Button
                    variant="contained"
                    size="large"
                    fullWidth
                    onClick={handleContinueToPayment}
                    disabled={!canProceed}
                  >
                    Continue to Payment
                  </Button>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default CheckoutPage;
