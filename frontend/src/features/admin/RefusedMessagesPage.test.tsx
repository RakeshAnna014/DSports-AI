import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import RefusedMessagesPage from './RefusedMessagesPage';
import { topicRefusalsApi } from '@/api/topicRefusals';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/topicRefusals', () => ({ topicRefusalsApi: { list: vi.fn(), csv: vi.fn() } }));
const api = vi.mocked(topicRefusalsApi);

const ROWS = [
  { time: '2026-11-24T10:00:00Z', topic: 'OFF_TOPIC' as const, role: 'GUEST' as const, text: 'what is the capital of India' },
  { time: '2026-11-24T09:00:00Z', topic: 'BUSINESS' as const, role: 'CUSTOMER' as const, text: 'what were our total sales' },
];

describe('RefusedMessagesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['ADMIN'] } });
    api.list.mockResolvedValue(ROWS);
  });

  it('shows topic, role, text and time for each refused message, and nothing about who asked', async () => {
    renderWithProviders(<RefusedMessagesPage />);
    const rows = await screen.findAllByTestId('refusal-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Off topic');
    expect(rows[0]).toHaveTextContent('Guest');
    expect(rows[0]).toHaveTextContent('what is the capital of India');
    expect(screen.queryByText(/customer id|conversation id/i)).toBeNull();
  });

  it('asks the server again with the chosen filter', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RefusedMessagesPage />);
    await screen.findAllByTestId('refusal-row');
    await user.click(screen.getByLabelText('Role'));
    await user.click(await screen.findByRole('option', { name: 'Customer' }));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ topic: undefined, role: 'CUSTOMER' }));
  });

  it('says so when nothing was refused', async () => {
    api.list.mockResolvedValue([]);
    renderWithProviders(<RefusedMessagesPage />);
    expect(await screen.findByTestId('no-refusals')).toBeInTheDocument();
    expect(screen.getByTestId('download-refusals')).toBeDisabled();
  });

  it('is for administrators only: anyone else sees no data and no request is made', () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-2', email: 'c@d.e', roles: ['CUSTOMER'] } });
    renderWithProviders(<RefusedMessagesPage />);
    expect(screen.getByText('Administrators only')).toBeInTheDocument();
    expect(api.list).not.toHaveBeenCalled();
  });
});
