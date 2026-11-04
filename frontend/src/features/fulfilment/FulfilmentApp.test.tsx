import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { renderWithProviders } from '@/test/render';
import FulfilmentApp from './FulfilmentApp';
import { fulfilmentApi, type FulfilmentRow } from '@/api/fulfilment';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/fulfilment', () => ({ fulfilmentApi: { board: vi.fn(), createShipment: vi.fn(), updateShipment: vi.fn() } }));

const row = (over: Partial<FulfilmentRow>): FulfilmentRow => ({
  orderId: 'o1', orderNumber: 'ORD-1', orderStatus: 'CONFIRMED', grandTotal: 2365, currency: 'INR', placedAt: null, items: 1, customer: 'a***@example.com', paymentStatus: 'SUCCESS',
  paid: true, shipmentId: 's1', shipmentNumber: 'SHP-1', trackingNumber: 'TRK-1', shipmentStatus: 'CREATED', courierProvider: null, canCreateShipment: false,
  nextShipmentStatuses: ['READY_FOR_PICKUP', 'FAILED'], ...over,
});
const asRole = (roles: string[]) => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'e@x.com', roles } });
const apiError = (status: number, message: string) =>
  new AxiosError(message, String(status), undefined, undefined, { status, data: { status, error: 'x', code: 'INVALID_STATUS_TRANSITION', message, path: '/p', correlationId: null, timestamp: '', validationErrors: null }, statusText: '', headers: {}, config: {} as never });

beforeEach(() => {
  vi.resetAllMocks();
  asRole(['ADMIN']);
});

describe('Fulfilment console', () => {
  it('is restricted to administrators and never calls the API for anyone else', () => {
    for (const roles of [['CUSTOMER'], ['SUPPORT_EXECUTIVE']]) {
      asRole(roles);
      const { unmount } = renderWithProviders(<FulfilmentApp />);
      expect(screen.getByTestId('ful-restricted')).toBeInTheDocument();
      unmount();
    }
    expect(fulfilmentApi.board).not.toHaveBeenCalled();
  });

  it('offers only the next valid step for a shipment, with delivery failure as a separate exception action', async () => {
    vi.mocked(fulfilmentApi.board).mockResolvedValue([row({})]);
    renderWithProviders(<FulfilmentApp />);
    const r = await screen.findByTestId('ful-row-ORD-1');
    expect(within(r).getByRole('button', { name: 'Ready for pickup' })).toBeInTheDocument();
    expect(within(r).getByRole('button', { name: 'Delivery failed' })).toBeInTheDocument();
    expect(within(r).queryByRole('button', { name: 'Mark delivered' })).not.toBeInTheDocument();            // not before dispatch
    expect(within(r).queryByRole('button', { name: /Dispatch/ })).not.toBeInTheDocument();
  });

  it('advances the shipment through the backend and shows that the order follows it', async () => {
    const user = userEvent.setup();
    vi.mocked(fulfilmentApi.board).mockResolvedValue([row({ shipmentStatus: 'OUT_FOR_DELIVERY', orderStatus: 'SHIPPED', nextShipmentStatuses: ['DELIVERED', 'FAILED'] })]);
    vi.mocked(fulfilmentApi.updateShipment).mockResolvedValue({});
    renderWithProviders(<FulfilmentApp />);
    await user.click(await screen.findByRole('button', { name: 'Mark delivered' }));
    await waitFor(() => expect(fulfilmentApi.updateShipment).toHaveBeenCalledWith('s1', 'DELIVERED', undefined));
    expect(await screen.findByTestId('ful-message')).toHaveTextContent('ORD-1: shipment delivered. The order status follows the shipment.');
  });

  it('records a delivery failure only with a reason', async () => {
    const user = userEvent.setup();
    vi.mocked(fulfilmentApi.board).mockResolvedValue([row({})]);
    vi.mocked(fulfilmentApi.updateShipment).mockResolvedValue({});
    renderWithProviders(<FulfilmentApp />);
    await user.click(await screen.findByRole('button', { name: 'Delivery failed' }));
    const confirm = screen.getByRole('button', { name: 'Record failure' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'Reason' }), 'Customer not available');
    await user.click(confirm);
    await waitFor(() => expect(fulfilmentApi.updateShipment).toHaveBeenCalledWith('s1', 'FAILED', 'Customer not available'));
  });

  it('never offers a step for an unpaid order, and says it is waiting for payment', async () => {
    vi.mocked(fulfilmentApi.board).mockResolvedValue([row({ orderStatus: 'PENDING_PAYMENT', paymentStatus: 'PROCESSING', paid: false, shipmentId: null, shipmentStatus: null, nextShipmentStatuses: [] })]);
    renderWithProviders(<FulfilmentApp />);
    await userEvent.setup().click(await screen.findByTestId('ful-tab-awaiting'));
    const r = await screen.findByTestId('ful-row-ORD-1');
    expect(within(r).queryAllByRole('button')).toHaveLength(0);
    expect(within(r).getByText(/Waiting for payment/)).toBeInTheDocument();
  });

  it('offers to create the shipment for a paid order that has none, and shows a delivered order as ready for Return AI', async () => {
    const user = userEvent.setup();
    vi.mocked(fulfilmentApi.board).mockResolvedValue([
      row({ orderNumber: 'ORD-2', orderId: 'o2', shipmentId: null, shipmentStatus: null, canCreateShipment: true, nextShipmentStatuses: [] }),
      row({ orderNumber: 'ORD-3', orderId: 'o3', orderStatus: 'DELIVERED', shipmentStatus: 'DELIVERED', nextShipmentStatuses: [] }),
    ]);
    vi.mocked(fulfilmentApi.createShipment).mockResolvedValue({});
    renderWithProviders(<FulfilmentApp />);
    await user.click(await screen.findByRole('button', { name: 'Create shipment' }));
    await waitFor(() => expect(fulfilmentApi.createShipment).toHaveBeenCalledWith('o2'));
    expect(within(screen.getByTestId('ful-row-ORD-3')).getByText('Delivered: ready for Return AI.')).toBeInTheDocument();
  });

  it('shows the backend message when a step is refused, and an empty state when there is nothing to do', async () => {
    const user = userEvent.setup();
    vi.mocked(fulfilmentApi.board).mockResolvedValueOnce([row({})]).mockResolvedValue([]);
    vi.mocked(fulfilmentApi.updateShipment).mockRejectedValue(apiError(409, 'Cannot transition from CREATED to PICKED_UP'));
    renderWithProviders(<FulfilmentApp />);
    await user.click(await screen.findByRole('button', { name: 'Ready for pickup' }));
    expect(await screen.findByTestId('ful-message')).toHaveTextContent('Cannot transition from CREATED to PICKED_UP');
    await user.click(screen.getByTestId('ful-tab-delivered'));
    expect(await screen.findByTestId('ful-empty')).toBeInTheDocument();
  });

  it('shows an error with a retry when the board cannot be loaded', async () => {
    vi.mocked(fulfilmentApi.board).mockRejectedValue(apiError(500, 'The orders could not be loaded right now'));
    renderWithProviders(<FulfilmentApp />);
    expect(await screen.findByTestId('ful-error')).toHaveTextContent('The orders could not be loaded right now');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
