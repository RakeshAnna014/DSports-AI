import { useEffect, useState } from 'react';
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
  IconButton,
  TextField,
  Collapse,
  Tooltip,
  MenuItem,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import {
  ShoppingBagOutlined,
  ArrowBack,
  HomeOutlined,
  CheckCircleOutline,
  LocalShippingOutlined,
  Add,
  Remove,
  DeleteOutline,
  AddCircleOutline,
} from '@mui/icons-material';
import type { CheckoutResponse } from '@/types/checkout';
import { checkoutApi } from '@/api/checkout';
import { cartApi } from '@/api/cart';
import type { CartItemResponse } from '@/api/cart';
import { ordersApi } from '@/api/orders';
import { getApiErrorMessage } from '@/api/errors';
import { formatPrice } from '@/lib/productUtils';
import apiClient from '@/api/client';
import PromoCodeSection from './PromoCodeSection';
import {
  COUNTRIES,
  EMPTY_ADDRESS,
  toAddressRequest,
  validateAddress,
  type AddressErrors,
  type NewAddressForm,
} from './addressValidation';

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

  const [showAddressForm, setShowAddressForm] = useState(false);
  const [savingAddress, setSavingAddress] = useState(false);
  const [newAddress, setNewAddress] = useState<NewAddressForm>(EMPTY_ADDRESS);
  const [addressErrors, setAddressErrors] = useState<AddressErrors>({});
  const [addressError, setAddressError] = useState<string | null>(null);
  const [addressTouched, setAddressTouched] = useState(false);


  const [cartItems, setCartItems] = useState<CartItemResponse[]>([]);

  useEffect(() => {
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
        if (!created?.id) {
          throw new Error('Invalid checkout creation response - missing id');
        }
        active = await checkoutApi.getById(created.id);
      }
      setCheckout(active);
      if (active.shippingAddressId) setSelectedAddress(active.shippingAddressId);
      if (active.deliveryMethodCode) {
        setSelectedDelivery(active.deliveryMethodCode);
      } else {
        try {
          const updated = await checkoutApi.selectDeliveryMethod(active.id, { deliveryMethodCode: 'STANDARD' });
          setCheckout(updated);
        } catch {
          // backend will reject validation if not set
        }
      }
      if (active.status === 'VALIDATED' || active.status === 'READY_FOR_PAYMENT') setValidated(true);

      const addrResp = await apiClient.get<{ addresses: Address[] }>('/customers/me/addresses');
      setAddresses(addrResp.data.addresses);

      try {
        const cartResp = await cartApi.getCart();
        setCartItems(cartResp.items || []);
      } catch {
        setCartItems([]);
      }
    } catch (err) {
      const msg = getApiErrorMessage(err, err instanceof Error ? err.message : 'Failed to initialize checkout');
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  const refreshCheckoutAndCart = async () => {
    if (!checkout) return;
    try {
      const [updated, cartResp] = await Promise.all([
        checkoutApi.getById(checkout.id),
        cartApi.getCart().catch(() => null),
      ]);
      setCheckout(updated);
      if (cartResp) setCartItems(cartResp.items || []);
      if (updated.status === 'VALIDATED' || updated.status === 'READY_FOR_PAYMENT') setValidated(true);
      if (!updated.items || updated.items.length === 0) navigate('/cart');
    } catch {
      navigate('/cart');
    }
  };

  const handleSelectAddress = async (addressId: string) => {
    if (!checkout || !addressId) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await checkoutApi.selectAddress(checkout.id, { addressId });
      setCheckout(updated);
      setSelectedAddress(addressId);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to select address'));
    } finally {
      setSubmitting(false);
    }
  };

  const updateAddressField = (field: keyof NewAddressForm, value: string) => {
    const next = { ...newAddress, [field]: value };
    setNewAddress(next);
    if (addressTouched) setAddressErrors(validateAddress(next));
  };

  const handleSaveAddress = async () => {
    setAddressTouched(true);
    const errors = validateAddress(newAddress);
    setAddressErrors(errors);
    if (Object.keys(errors).length > 0) {
      setAddressError('Please correct the highlighted fields.');
      return;
    }
    setSavingAddress(true);
    setAddressError(null);
    try {
      const resp = await apiClient.post<Address>('/customers/me/addresses', toAddressRequest(newAddress));
      const saved = resp.data;
      if (!saved || !saved.addressId) {
        throw new Error('Invalid address response from server');
      }
      const addrResp = await apiClient.get<{ addresses: Address[] }>('/customers/me/addresses');
      setAddresses(addrResp.data.addresses);
      setShowAddressForm(false);
      setNewAddress(EMPTY_ADDRESS);
      setAddressErrors({});
      setAddressTouched(false);
      await handleSelectAddress(saved.addressId);
    } catch (err) {
      // Kept inside the form: the customer's input and the cart/checkout state are untouched, so they can correct and retry.
      setAddressError(getApiErrorMessage(err, err instanceof Error && err.message ? err.message : 'The address could not be saved. Check your connection and try again.'));
    } finally {
      setSavingAddress(false);
    }
  };

  const handleSelectDelivery = async (code: string) => {
    if (!checkout) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await checkoutApi.selectDeliveryMethod(checkout.id, {
        deliveryMethodCode: code as 'STANDARD' | 'EXPRESS' | 'NEXT_DAY',
      });
      setCheckout(updated);
      setSelectedDelivery(code);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to select delivery method'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleUpdateQuantity = async (productId: string, delta: number) => {
    const cartItem = cartItems.find((ci) => ci.productId === productId);
    if (!cartItem) return;
    const newQty = cartItem.quantity + delta;
    if (newQty <= 0) {
      await handleRemoveItem(productId);
      return;
    }
    setSubmitting(true);
    try {
      await cartApi.updateQuantity(cartItem.id, newQty);
      await refreshCheckoutAndCart();
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to update quantity'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleRemoveItem = async (productId: string) => {
    const cartItem = cartItems.find((ci) => ci.productId === productId);
    if (!cartItem) return;
    setSubmitting(true);
    try {
      await cartApi.removeItem(cartItem.id);
      await refreshCheckoutAndCart();
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to remove item'));
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
    } catch (err) {
      setError(getApiErrorMessage(err, 'Validation failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleContinueToPayment = async () => {
    if (!checkout) return;
    setSubmitting(true);
    setError(null);
    try {
      const order = await ordersApi.placeOrder({ checkoutId: checkout.id });
      navigate(`/payment?orderId=${order.id}`);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to place order'));
    } finally {
      setSubmitting(false);
    }
  };

  const canValidate = checkout && selectedAddress && selectedDelivery && !validated;
  const canProceed = validated && (checkout?.status === 'VALIDATED' || checkout?.status === 'READY_FOR_PAYMENT');

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

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}

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
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                  <Typography variant="h6" fontWeight={600}>
                    Shipping Address
                  </Typography>
                  <Button
                    size="small"
                    startIcon={<AddCircleOutline />}
                    onClick={() => setShowAddressForm(!showAddressForm)}
                  >
                    {showAddressForm ? 'Cancel' : 'Add New'}
                  </Button>
                </Box>

                <Collapse in={showAddressForm}>
                  <Stack spacing={2} sx={{ mb: 2, p: 2, bgcolor: 'action.hover', borderRadius: 1 }} component="form" noValidate
                    onSubmit={(e) => { e.preventDefault(); handleSaveAddress(); }} aria-label="New shipping address">
                    {addressError && <Alert severity="error" role="alert">{addressError}</Alert>}
                    <TextField
                      label="Address Line 1"
                      required
                      size="small"
                      value={newAddress.line1}
                      onChange={(e) => updateAddressField('line1', e.target.value)}
                      error={!!addressErrors.line1}
                      helperText={addressErrors.line1}
                      slotProps={{ htmlInput: { maxLength: 255, autoComplete: 'address-line1' } }}
                    />
                    <TextField
                      label="Address Line 2"
                      size="small"
                      value={newAddress.line2}
                      onChange={(e) => updateAddressField('line2', e.target.value)}
                      error={!!addressErrors.line2}
                      helperText={addressErrors.line2}
                      slotProps={{ htmlInput: { maxLength: 255, autoComplete: 'address-line2' } }}
                    />
                    <Box sx={{ display: 'flex', gap: 2 }}>
                      <TextField
                        label="City"
                        required
                        size="small"
                        sx={{ flex: 1 }}
                        value={newAddress.city}
                        onChange={(e) => updateAddressField('city', e.target.value)}
                        error={!!addressErrors.city}
                        helperText={addressErrors.city}
                        slotProps={{ htmlInput: { maxLength: 100, autoComplete: 'address-level2' } }}
                      />
                      <TextField
                        label="State"
                        required
                        size="small"
                        sx={{ flex: 1 }}
                        value={newAddress.state}
                        onChange={(e) => updateAddressField('state', e.target.value)}
                        error={!!addressErrors.state}
                        helperText={addressErrors.state}
                        slotProps={{ htmlInput: { maxLength: 100, autoComplete: 'address-level1' } }}
                      />
                    </Box>
                    <Box sx={{ display: 'flex', gap: 2 }}>
                      <TextField
                        label="Postal Code"
                        required
                        size="small"
                        sx={{ flex: 1 }}
                        value={newAddress.postalCode}
                        onChange={(e) => updateAddressField('postalCode', e.target.value)}
                        error={!!addressErrors.postalCode}
                        helperText={addressErrors.postalCode}
                        slotProps={{ htmlInput: { maxLength: 20, autoComplete: 'postal-code' } }}
                      />
                      <TextField
                        select
                        label="Country"
                        required
                        size="small"
                        sx={{ flex: 1 }}
                        value={newAddress.country}
                        onChange={(e) => updateAddressField('country', e.target.value)}
                        error={!!addressErrors.country}
                        helperText={addressErrors.country}
                      >
                        {COUNTRIES.map((c) => (
                          <MenuItem key={c.code} value={c.code}>{c.name}</MenuItem>
                        ))}
                      </TextField>
                    </Box>
                    <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <Button
                        type="submit"
                        variant="contained"
                        size="small"
                        disabled={savingAddress}
                      >
                        {savingAddress ? <CircularProgress size={20} aria-label="Saving address" /> : 'Save Address'}
                      </Button>
                    </Box>
                  </Stack>
                </Collapse>

                {addresses.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    No saved addresses. Click "Add New" above to add one.
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
                  Items ({checkout.items.length})
                </Typography>
                {checkout.items.map((item) => {
                  const cartItem = cartItems.find((ci) => ci.productId === item.productId);
                  return (
                    <Box
                      key={item.id}
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        py: 1,
                        borderBottom: '1px solid',
                        borderColor: 'divider',
                        '&:last-child': { borderBottom: 'none' },
                      }}
                    >
                      <Box sx={{ flex: 1 }}>
                        <Typography variant="body2" fontWeight={500}>{item.productName}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {formatPrice(item.unitPrice)} each
                        </Typography>
                      </Box>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mx: 2 }}>
                        <Tooltip title="Decrease quantity">
                          <span>
                            <IconButton
                              size="small"
                              onClick={() => handleUpdateQuantity(item.productId, -1)}
                              disabled={submitting || !cartItem}
                            >
                              <Remove fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Typography variant="body2" fontWeight={600} sx={{ minWidth: 24, textAlign: 'center' }}>
                          {item.quantity}
                        </Typography>
                        <Tooltip title="Increase quantity">
                          <span>
                            <IconButton
                              size="small"
                              onClick={() => handleUpdateQuantity(item.productId, 1)}
                              disabled={submitting || !cartItem}
                            >
                              <Add fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                      </Box>
                      <Typography variant="body2" fontWeight={600} sx={{ minWidth: 80, textAlign: 'right' }}>
                        {formatPrice(item.lineTotal)}
                      </Typography>
                      <Tooltip title="Remove item">
                        <span>
                          <IconButton
                            size="small"
                            color="error"
                            onClick={() => handleRemoveItem(item.productId)}
                            disabled={submitting || !cartItem}
                            sx={{ ml: 1 }}
                          >
                            <DeleteOutline fontSize="small" />
                          </IconButton>
                        </span>
                      </Tooltip>
                    </Box>
                  );
                })}
              </CardContent>
            </Card>

            <PromoCodeSection checkout={checkout} busy={submitting} onChange={setCheckout} />
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
                <Typography variant="body2">{formatPrice(checkout.subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Shipping</Typography>
                <Typography variant="body2">{formatPrice(checkout.deliveryCharge)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Tax (18%)</Typography>
                <Typography variant="body2">{formatPrice(checkout.taxAmount)}</Typography>
              </Box>
              {Number(checkout.discountAmount) > 0 && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography variant="body2" color="success.main">{checkout.promotionCode ? `Discount (${checkout.promotionCode})` : 'Discount'}</Typography>
                  <Typography variant="body2" color="success.main">-{formatPrice(checkout.discountAmount)}</Typography>
                </Box>
              )}
              <Divider sx={{ my: 1.5 }} />
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="subtitle1" fontWeight={700}>Grand Total</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="primary.main">
                  {formatPrice(checkout.totalAmount)}
                </Typography>
              </Box>

              <Chip
                label={`Status: ${checkout.status.replace('_', ' ')}`}
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
                    disabled={!canProceed || submitting}
                  >
                    {submitting ? <CircularProgress size={24} /> : 'Continue to Payment'}
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
