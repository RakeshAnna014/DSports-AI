import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { renderWithProviders } from '@/test/render';
import PromoCodeSection from './PromoCodeSection';
import CheckoutPage from './CheckoutPage';
import { checkoutApi } from '@/api/checkout';
import { cartApi } from '@/api/cart';
import { aiApi } from '@/api/ai';
import apiClient from '@/api/client';
import type { CheckoutResponse, OfferRecommendations } from '@/types/checkout';

vi.mock('@/api/checkout', () => ({ checkoutApi: { getActive: vi.fn(), create: vi.fn(), getById: vi.fn(), cancel: vi.fn(), selectDeliveryMethod: vi.fn(), selectAddress: vi.fn(), validate: vi.fn(), listPromotions: vi.fn(), applyPromotion: vi.fn(), removePromotion: vi.fn() } }));
vi.mock('@/api/cart', () => ({ cartApi: { getCart: vi.fn(), updateQuantity: vi.fn(), removeItem: vi.fn() } }));
vi.mock('@/api/orders', () => ({ ordersApi: { placeOrder: vi.fn() } }));
vi.mock('@/api/ai', () => ({ aiApi: { recommendOffers: vi.fn() } }));
vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const CHECKOUT: CheckoutResponse = {
  id: 'co-1', customerId: 'u', cartId: 'c', status: 'PENDING', shippingAddressId: null, deliveryMethodCode: 'STANDARD', deliveryMethodName: 'Standard', deliveryCharge: 5,
  discountAmount: 0, promotionCode: null, subtotal: 2000, taxAmount: 360, totalAmount: 2365, currency: 'INR', notes: null, expiresAt: '', validatedAt: null, version: 1, createdAt: '', updatedAt: '',
  items: [{ id: 'i1', productId: 'p1', productName: 'Bat', sku: 'B-1', quantity: 1, unitPrice: 2000, lineTotal: 2000, imageUrl: null }],
};
const WITH_PROMO: CheckoutResponse = { ...CHECKOUT, promotionCode: 'CRICKET15', discountAmount: 300, totalAmount: 2065 };

const base = { scope: 'ALL' as const, scopeNames: [], validUntil: null, eligibleSubtotal: 2000, applied: false, demo: true };
const RECS: OfferRecommendations = {
  checkoutId: 'co-1', personalized: true, personalizationOff: false, basedOn: ['your cart', 'your order history'], message: null,
  disclaimer: 'Offers marked DEMO are synthetic demonstration offers, not real promotions.',
  offers: [
    { ...base, code: 'CRICKET15', title: 'Cricket gear offer', description: 'DEMO OFFER (synthetic): 15% off cricket items', eligible: true, discountAmount: '300.00', ineligibleReason: null,
      reasons: ['Covers 1 Cricket item(s) in your cart (₹2,000 of eligible items)', 'You have bought Cricket gear before (1 of your recent purchases)', 'Saves ₹300 on this cart'], personal: true, recommended: true },
    { ...base, code: 'FLAT200', title: 'Flat INR 200 off', description: 'DEMO OFFER (synthetic): INR 200 off', eligible: false, discountAmount: '0.00',
      ineligibleReason: 'Add INR 1000.00 more of eligible items to use this offer (minimum INR 3000.00).', reasons: [], personal: false },
  ],
};

const apiError = (status: number, code: string, message: string) =>
  new AxiosError(message, String(status), undefined, undefined, { status, data: { status, error: 'x', code, message, path: '/p', correlationId: null, timestamp: '', validationErrors: null }, statusText: '', headers: {}, config: {} as never });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(aiApi.recommendOffers).mockResolvedValue(RECS);
});

describe('PromoCodeSection', () => {
  it('shows the AI-ranked offers with grounded reasons, the DEMO label and the amount the backend calculated', async () => {
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    const best = await screen.findByTestId('offer-CRICKET15');
    expect(within(best).getByText('Recommended for you')).toBeInTheDocument();
    expect(within(best).getByText('DEMO')).toBeInTheDocument();
    expect(within(best).getByText(/Save ₹300/)).toBeInTheDocument();
    expect(within(best).getByText(/You have bought Cricket gear before/)).toBeInTheDocument();
    expect(screen.getByText(/Based on your cart, your order history/)).toBeInTheDocument();
  });

  it('an ineligible offer cannot be applied and says why, using the backend reason', async () => {
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    const flat = await screen.findByTestId('offer-FLAT200');
    expect(within(flat).queryByRole('button', { name: /apply/i })).not.toBeInTheDocument();
    expect(within(flat).getByText(/Add INR 1000.00 more/)).toBeInTheDocument();
  });

  it('applies a recommended offer through the backend and hands the recalculated checkout to the page', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    vi.mocked(checkoutApi.applyPromotion).mockResolvedValue(WITH_PROMO);
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={onChange} />);
    await user.click(await screen.findByRole('button', { name: 'Apply CRICKET15' }));
    await waitFor(() => expect(checkoutApi.applyPromotion).toHaveBeenCalledWith('co-1', 'CRICKET15'));
    expect(onChange).toHaveBeenCalledWith(WITH_PROMO);
    expect(await screen.findByText(/CRICKET15 applied: you save ₹300/)).toBeInTheDocument();
  });

  it('applies a typed code in upper case and shows the backend message when it is rejected, changing nothing', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    vi.mocked(checkoutApi.applyPromotion).mockRejectedValue(apiError(400, 'PROMOTION_EXPIRED', 'This offer has expired.'));
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={onChange} />);
    await user.type(await screen.findByRole('textbox', { name: 'Promo code' }), 'oldfest5');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('This offer has expired.')).toBeInTheDocument();
    expect(checkoutApi.applyPromotion).toHaveBeenCalledWith('co-1', 'OLDFEST5');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('refuses an empty code locally without a request', async () => {
    const user = userEvent.setup();
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    await screen.findByTestId('offer-CRICKET15');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'Promo code' }), '   ');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
    expect(checkoutApi.applyPromotion).not.toHaveBeenCalled();
  });

  it('removes an applied code', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    vi.mocked(checkoutApi.removePromotion).mockResolvedValue(CHECKOUT);
    render(<PromoCodeSection checkout={WITH_PROMO} busy={false} onChange={onChange} />);
    expect(await screen.findByTestId('promo-applied')).toHaveTextContent('CRICKET15 applied: you save ₹300');
    await user.click(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(checkoutApi.removePromotion).toHaveBeenCalledWith('co-1'));
    expect(onChange).toHaveBeenCalledWith(CHECKOUT);
  });

  it('after validation the promotion is read-only: no input, no remove, no apply', async () => {
    render(<PromoCodeSection checkout={{ ...WITH_PROMO, status: 'VALIDATED' }} busy={false} onChange={vi.fn()} />);
    expect(await screen.findByText(/cannot be changed after the checkout is validated/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Promo code' })).not.toBeInTheDocument();
  });

  it('falls back to the backend offers with a notice when the AI service is unreachable', async () => {
    vi.mocked(aiApi.recommendOffers).mockRejectedValue(new Error('Network Error'));
    vi.mocked(checkoutApi.listPromotions).mockResolvedValue([{ ...RECS.offers[0], discountType: 'PERCENT', discountValue: 15, maxDiscountAmount: 750, minOrderAmount: 1000 }]);
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    expect(await screen.findByText(/Personalised suggestions are unavailable/)).toBeInTheDocument();
    expect(screen.getByTestId('offer-CRICKET15')).toBeInTheDocument();
  });

  it('says plainly that no offer applies, without inventing one', async () => {
    vi.mocked(aiApi.recommendOffers).mockResolvedValue({ ...RECS, personalized: false, offers: [], message: 'No offer applies to this cart right now.' });
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    expect(await screen.findByTestId('promo-none')).toHaveTextContent('No offer applies to this cart right now.');
    expect(screen.queryByTestId('promo-offers')).not.toBeInTheDocument();
  });

  it('offers a retry when neither service can list offers, while a code can still be typed', async () => {
    vi.mocked(aiApi.recommendOffers).mockRejectedValue(new Error('down'));
    vi.mocked(checkoutApi.listPromotions).mockRejectedValue(new Error('down'));
    render(<PromoCodeSection checkout={CHECKOUT} busy={false} onChange={vi.fn()} />);
    expect(await screen.findByText(/Offers could not be loaded/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Promo code' })).toBeEnabled();
  });
});

describe('checkout summary with a promotion', () => {
  it('shows the discount line and the grand total exactly as the backend returned them', async () => {
    const user = userEvent.setup();
    vi.mocked(checkoutApi.getActive).mockResolvedValue(CHECKOUT);
    vi.mocked(cartApi.getCart).mockResolvedValue({ items: [{ id: 'ci', productId: 'p1', quantity: 1 }] } as never);
    vi.mocked(apiClient.get).mockResolvedValue({ data: { addresses: [] } } as never);
    vi.mocked(checkoutApi.applyPromotion).mockResolvedValue(WITH_PROMO);
    renderWithProviders(<CheckoutPage />);
    await user.click(await screen.findByRole('button', { name: 'Apply CRICKET15' }));
    expect(await screen.findByText('Discount (CRICKET15)')).toBeInTheDocument();
    expect(screen.getByText('-₹300.00')).toBeInTheDocument();
    expect(screen.getByText('₹2,065.00')).toBeInTheDocument();
  });
});
