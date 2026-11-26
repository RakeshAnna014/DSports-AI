import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import AdminGroundOwnersPage from './AdminGroundOwnersPage';
import { adminApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { apiError } from '@/test/venueFixtures';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, adminApi: { owners: vi.fn(), grantOwner: vi.fn(), removeOwner: vi.fn(), ownerAudit: vi.fn() } };
});

const OWNER = { id: 'o-1', email: 'asha@example.com', firstName: 'Asha', lastName: 'Rao', grantedAt: '2026-11-16T10:00:00Z' };

describe('AdminGroundOwnersPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'admin-1', email: 'admin@b.c', roles: ['CUSTOMER', 'ADMIN'] } });
    vi.mocked(adminApi.owners).mockResolvedValue([OWNER]);
    vi.mocked(adminApi.ownerAudit).mockResolvedValue([
      { id: 'a-1', actorId: 'admin-1', targetId: 'o-1', targetEmail: 'asha@example.com', action: 'GRANTED', occurredAt: '2026-11-16T10:00:00Z' },
    ]);
  });

  it('is for administrators only and loads nothing for anyone else', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER', 'GROUND_OWNER'] } });
    renderWithProviders(<AdminGroundOwnersPage />);

    expect(await screen.findByText(/administrators only/i)).toBeInTheDocument();
    expect(adminApi.owners).not.toHaveBeenCalled();
    expect(adminApi.grantOwner).not.toHaveBeenCalled();
  });

  it('lists the current owners and the audit log, naming the signed-in administrator as "you"', async () => {
    renderWithProviders(<AdminGroundOwnersPage />);

    const rows = await screen.findAllByTestId('owner-row');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('asha@example.com');
    expect(rows[0]).toHaveTextContent('Asha Rao');
    expect(await screen.findByTestId('owner-audit')).toHaveTextContent(/Role given to asha@example.com by you/);
  });

  it('gives the role to a registered user and tells the admin they must sign in again', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.grantOwner).mockResolvedValue({ id: 'o-2', email: 'ben@example.com', changed: true });
    renderWithProviders(<AdminGroundOwnersPage />);
    await screen.findAllByTestId('owner-row');

    expect(screen.getByTestId('grant-owner')).toBeDisabled();
    await user.type(screen.getByLabelText(/email of a registered user/i), ' ben@example.com ');
    await user.click(screen.getByTestId('grant-owner'));

    expect(await screen.findByTestId('owners-message')).toHaveTextContent(/ben@example.com is now a ground owner.*sign in again/i);
    expect(adminApi.grantOwner).toHaveBeenCalledWith('ben@example.com');
  });

  it('says so when the user already is an owner', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.grantOwner).mockResolvedValue({ id: 'o-1', email: 'asha@example.com', changed: false });
    renderWithProviders(<AdminGroundOwnersPage />);
    await user.type(await screen.findByLabelText(/email of a registered user/i), 'asha@example.com');
    await user.click(screen.getByTestId('grant-owner'));

    expect(await screen.findByTestId('owners-message')).toHaveTextContent(/already is a ground owner/i);
  });

  it('shows "Ask them to register first" for an email nobody registered with', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.grantOwner).mockRejectedValue(apiError(404, 'USER_NOT_REGISTERED', 'No registered user has that email. Ask them to register first.'));
    renderWithProviders(<AdminGroundOwnersPage />);
    await user.type(await screen.findByLabelText(/email of a registered user/i), 'nobody@example.com');
    await user.click(screen.getByTestId('grant-owner'));

    expect(await screen.findByTestId('owners-message')).toHaveTextContent(/ask them to register first/i);
  });

  it('shows the server\'s refusal when an administrator tries to make themselves an owner', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.grantOwner).mockRejectedValue(apiError(403, 'CANNOT_CHANGE_OWN_ROLE', 'You cannot grant a role to yourself'));
    renderWithProviders(<AdminGroundOwnersPage />);
    await user.type(await screen.findByLabelText(/email of a registered user/i), 'admin@b.c');
    await user.click(screen.getByTestId('grant-owner'));

    expect(await screen.findByTestId('owners-message')).toHaveTextContent(/cannot give the role to yourself/i);
  });

  it('asks before removing the role, explains the consequences, then removes it', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.removeOwner).mockResolvedValue(undefined);
    renderWithProviders(<AdminGroundOwnersPage />);
    await user.click(await screen.findByTestId('remove-owner'));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(/stop taking new bookings.*existing bookings stay valid.*15 minutes/is);
    expect(adminApi.removeOwner).not.toHaveBeenCalled();
    await user.click(within(dialog).getByTestId('confirm-remove-owner'));

    await waitFor(() => expect(adminApi.removeOwner).toHaveBeenCalledWith('o-1'));
    expect(await screen.findByTestId('owners-message')).toHaveTextContent(/role was removed.*reassign the venues/i);
  });

  it('keeps the role when the administrator changes their mind', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminGroundOwnersPage />);
    await user.click(await screen.findByTestId('remove-owner'));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /keep role/i }));

    expect(adminApi.removeOwner).not.toHaveBeenCalled();
  });
});
