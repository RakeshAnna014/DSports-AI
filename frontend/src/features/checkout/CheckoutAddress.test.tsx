import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { renderWithProviders } from '@/test/render';
import CheckoutPage from './CheckoutPage';
import { validateAddress, toAddressRequest, EMPTY_ADDRESS } from './addressValidation';
import { checkoutApi } from '@/api/checkout';
import { cartApi } from '@/api/cart';
import { aiApi } from '@/api/ai';
import apiClient from '@/api/client';

vi.mock('@/api/checkout', () => ({ checkoutApi: { getActive: vi.fn(), create: vi.fn(), getById: vi.fn(), cancel: vi.fn(), selectDeliveryMethod: vi.fn(), selectAddress: vi.fn(), validate: vi.fn(), listPromotions: vi.fn(), applyPromotion: vi.fn(), removePromotion: vi.fn() } }));
vi.mock('@/api/ai', () => ({ aiApi: { recommendOffers: vi.fn() } }));
vi.mock('@/api/cart', () => ({ cartApi: { getCart: vi.fn(), updateQuantity: vi.fn(), removeItem: vi.fn() } }));
vi.mock('@/api/orders', () => ({ ordersApi: { placeOrder: vi.fn() } }));
vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const CHECKOUT = {
  id: 'co-1', customerId: 'u', cartId: 'c', status: 'DRAFT', shippingAddressId: null, deliveryMethodCode: 'STANDARD', deliveryMethodName: 'Standard', deliveryCharge: 5,
  discountAmount: 0, promotionCode: null, subtotal: 1000, taxAmount: 50, totalAmount: 1055, currency: 'INR', notes: null, expiresAt: '', validatedAt: null, version: 1, createdAt: '', updatedAt: '',
  items: [{ id: 'i1', productId: 'p1', productName: 'Bat', sku: 'B-1', quantity: 1, unitPrice: 1000, lineTotal: 1000, imageUrl: null }],
};
const SAVED = { addressId: 'a-1', type: 'SHIPPING', line1: '12 MG Road', line2: '', city: 'Pune', state: 'MH', country: 'IN', postalCode: '411001', isDefault: true };

const apiError = (status: number, code: string, message: string) =>
  new AxiosError(message, String(status), undefined, undefined, { status, data: { status, error: 'x', code, message, path: '/p', correlationId: null, timestamp: '', validationErrors: null }, statusText: '', headers: {}, config: {} as never });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(checkoutApi.getActive).mockResolvedValue(CHECKOUT as never);
  vi.mocked(cartApi.getCart).mockResolvedValue({ items: [{ id: 'ci', productId: 'p1', quantity: 1 }] } as never);
  vi.mocked(apiClient.get).mockResolvedValue({ data: { addresses: [] } } as never);
  vi.mocked(aiApi.recommendOffers).mockRejectedValue(new Error('offers are not part of these tests'));
  vi.mocked(checkoutApi.listPromotions).mockResolvedValue([]);
});

const fill = async (user: ReturnType<typeof userEvent.setup>, overrides: Partial<Record<'Address Line 1' | 'City' | 'State' | 'Postal Code', string>> = {}) => {
  const v = { 'Address Line 1': '12 MG Road', City: 'Pune', State: 'MH', 'Postal Code': '411001', ...overrides };
  for (const [label, value] of Object.entries(v)) {
    const field = screen.getByRole('textbox', { name: new RegExp(`^${label}`) });
    await user.clear(field);
    if (value) await user.type(field, value);
  }
};

describe('address validation rules (mirror the backend)', () => {
  it('accepts a complete address and builds a trimmed SHIPPING request with an alpha-2 country', () => {
    const form = { line1: ' 12 MG Road ', line2: '  ', city: 'Pune', state: 'MH', country: 'IN', postalCode: '411001' };
    expect(validateAddress(form)).toEqual({});
    expect(toAddressRequest(form)).toEqual({ line1: '12 MG Road', line2: undefined, city: 'Pune', state: 'MH', country: 'IN', postalCode: '411001', type: 'SHIPPING' });
  });

  it('rejects blanks, a non-alphanumeric postal code, over-long values and a country that is not an ISO alpha-2 code', () => {
    const e = validateAddress({ ...EMPTY_ADDRESS, country: 'India', postalCode: '411 001', city: 'x'.repeat(101) });
    expect(Object.keys(e).sort()).toEqual(['city', 'country', 'line1', 'postalCode', 'state']);
    expect(validateAddress({ ...EMPTY_ADDRESS, line1: 'a', city: 'b', state: 'c', postalCode: 'x'.repeat(21) }).postalCode).toMatch(/at most 20/);
  });
});

describe('checkout: adding and saving a shipping address', () => {
  it('saves a valid address with country IN, shows it, and selects it for this checkout', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.post).mockResolvedValue({ data: SAVED } as never);
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { addresses: [] } } as never).mockResolvedValue({ data: { addresses: [SAVED] } } as never);
    vi.mocked(checkoutApi.selectAddress).mockResolvedValue({ ...CHECKOUT, shippingAddressId: 'a-1' } as never);
    renderWithProviders(<CheckoutPage />);
    await user.click(await screen.findByRole('button', { name: /add new/i }));
    await fill(user);
    await user.click(screen.getByRole('button', { name: /save address/i }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/customers/me/addresses', { line1: '12 MG Road', line2: undefined, city: 'Pune', state: 'MH', country: 'IN', postalCode: '411001', type: 'SHIPPING' }));
    await waitFor(() => expect(checkoutApi.selectAddress).toHaveBeenCalledWith('co-1', { addressId: 'a-1' }));
    expect(await screen.findByText('12 MG Road')).toBeInTheDocument();
    expect(screen.queryByText(/unexpected error/i)).not.toBeInTheDocument();
  });

  it('blocks an incomplete address locally with field messages and sends nothing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<CheckoutPage />);
    await user.click(await screen.findByRole('button', { name: /add new/i }));
    await user.click(screen.getByRole('button', { name: /save address/i }));
    expect(await screen.findByText('Enter the street address')).toBeInTheDocument();
    expect(screen.getByText('Enter the postal code')).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it('shows the backend message inside the form on a validation failure, keeps the typed values, the cart and the checkout', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.post).mockRejectedValue(apiError(400, 'INVALID_POSTAL_CODE', 'Postal code must be alphanumeric'));
    renderWithProviders(<CheckoutPage />);
    await user.click(await screen.findByRole('button', { name: /add new/i }));
    await fill(user);
    await user.click(screen.getByRole('button', { name: /save address/i }));
    expect(await screen.findByText('Postal code must be alphanumeric')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /^Address Line 1/ })).toHaveValue('12 MG Road');
    expect(screen.getByText('Bat')).toBeInTheDocument();
  });

  it('reports a network failure inside the form and lets the customer retry', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.post).mockRejectedValueOnce(new Error('Network Error')).mockResolvedValue({ data: SAVED } as never);
    vi.mocked(checkoutApi.selectAddress).mockResolvedValue({ ...CHECKOUT, shippingAddressId: 'a-1' } as never);
    renderWithProviders(<CheckoutPage />);
    await user.click(await screen.findByRole('button', { name: /add new/i }));
    await fill(user);
    await user.click(screen.getByRole('button', { name: /save address/i }));
    expect(await screen.findByText('Network Error')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /save address/i }));
    await waitFor(() => expect(checkoutApi.selectAddress).toHaveBeenCalled());
  });

  it('lets the customer pick an already saved address, and validation stays disabled until one is selected', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.get).mockResolvedValue({ data: { addresses: [SAVED] } } as never);
    vi.mocked(checkoutApi.selectAddress).mockResolvedValue({ ...CHECKOUT, shippingAddressId: 'a-1' } as never);
    renderWithProviders(<CheckoutPage />);
    const validate = await screen.findByRole('button', { name: /validate/i });
    expect(validate).toBeDisabled();
    await user.click(await screen.findByText('12 MG Road'));
    await waitFor(() => expect(checkoutApi.selectAddress).toHaveBeenCalledWith('co-1', { addressId: 'a-1' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /validate/i })).toBeEnabled());
  });
});
