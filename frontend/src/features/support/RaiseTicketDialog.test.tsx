import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { pick, renderWithProviders } from '@/test/render';
import RaiseTicketDialog from './RaiseTicketDialog';
import { supportApi } from '@/api/support';
import { ordersApi } from '@/api/orders';

vi.mock('@/api/orders', () => ({ ordersApi: { getOrders: vi.fn() } }));
vi.mock('@/api/support', async (orig) => {
  const real = await orig<typeof import('@/api/support')>();
  return { ...real, supportApi: { ...real.supportApi, create: vi.fn() } };
});

const ORDER = { id: 'o-1', orderNumber: 'ORD-20261001-000001', status: 'DELIVERED', totalItems: 1, grandTotal: 100, currency: 'INR', placedAt: '2026-10-01T00:00:00Z' };

const fill = async (user: ReturnType<typeof userEvent.setup>) => {
  await pick(user, /category/i, 'Payment');
  await user.type(screen.getByLabelText(/subject/i), 'Charged twice');
  await user.type(screen.getByLabelText(/describe the problem/i), 'My card was debited two times');
};

describe('RaiseTicketDialog', () => {
  beforeEach(() => {
    vi.mocked(ordersApi.getOrders).mockResolvedValue([ORDER]);
  });

  it('offers every customer-facing category from the brief and cannot be reviewed until the required fields are filled', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RaiseTicketDialog open onClose={() => {}} onCreated={() => {}} />);
    expect(screen.getByTestId('ticket-review-btn')).toBeDisabled();
    await user.click(screen.getByRole('combobox', { name: /category/i }));
    const labels = within(screen.getByRole('listbox')).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual(['Order', 'Payment', 'Delivery / shipment', 'Product', 'Return / refund', 'Website / technical', 'Other']);
  });

  it('shows a review step and submits the category, subject, description and the chosen order with an Idempotency-Key, and never a customer id', async () => {
    const user = userEvent.setup();
    const onCreated = vi.fn();
    vi.mocked(supportApi.create).mockResolvedValue({ outcome: 'CREATED', supportCase: { caseNumber: 'CASE-20261013-000007' } as never });
    renderWithProviders(<RaiseTicketDialog open onClose={() => {}} onCreated={onCreated} />);
    await fill(user);
    await pick(user, /related order/i, /ORD-20261001-000001/);
    await user.click(screen.getByTestId('ticket-review-btn'));

    const review = screen.getByTestId('ticket-review');
    expect(review).toHaveTextContent('Payment');
    expect(review).toHaveTextContent('Charged twice');
    expect(review).toHaveTextContent('ORD-20261001-000001');
    expect(supportApi.create).not.toHaveBeenCalled();                                   // nothing is sent before the explicit submit

    await user.click(screen.getByTestId('ticket-submit'));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('CASE-20261013-000007'));
    const [payload, key] = vi.mocked(supportApi.create).mock.calls[0];
    expect(payload).toEqual({ category: 'PAYMENT_ISSUE', subject: 'Charged twice', description: 'My card was debited two times', orderId: 'o-1' });
    expect(Object.keys(payload).some((k) => /user|customer/i.test(k))).toBe(false);
    expect(key).toMatch(/^web-/);
  });

  it('shows the backend message when creation fails and keeps the form so nothing is lost', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.create).mockRejectedValue({ isAxiosError: true, response: { status: 400, data: { code: 'VALIDATION_ERROR', message: 'A subject of 1-200 characters is required' } } });
    renderWithProviders(<RaiseTicketDialog open onClose={() => {}} onCreated={() => {}} />);
    await fill(user);
    await user.click(screen.getByTestId('ticket-review-btn'));
    await user.click(screen.getByTestId('ticket-submit'));
    expect(await screen.findByTestId('ticket-error')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /back/i }));
    expect(screen.getByLabelText(/subject/i)).toHaveValue('Charged twice');
  });

  it('still allows a ticket without an order when the orders list cannot be loaded', async () => {
    vi.mocked(ordersApi.getOrders).mockRejectedValue(new Error('down'));
    renderWithProviders(<RaiseTicketDialog open onClose={() => {}} onCreated={() => {}} />);
    expect(await screen.findByText(/can still submit without one/i)).toBeInTheDocument();
  });
});
