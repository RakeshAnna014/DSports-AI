import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import PlayersPage from './PlayersPage';
import { cricketApi } from '@/api/cricket';
import { useAuthStore } from '@/store/authStore';
import { apiError } from '@/test/venueFixtures';
import { CLAIM, OWN, PLAYER, PLAYER_2 } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({
  cricketApi: {
    players: {
      mine: vi.fn(), createGuest: vi.fn(), createOwn: vi.fn(), claimable: vi.fn(), claim: vi.fn(),
      incomingClaims: vi.fn(), myClaims: vi.fn(), approveClaim: vi.fn(), rejectClaim: vi.fn(),
    },
  },
}));
const api = vi.mocked(cricketApi.players);

describe('PlayersPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    api.mine.mockResolvedValue([PLAYER, PLAYER_2]);
    api.incomingClaims.mockResolvedValue([]);
    api.myClaims.mockResolvedValue([]);
  });

  it('lists the players I added with the phone only where the server sent it', async () => {
    renderPage();

    const rows = await screen.findAllByTestId('player-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('+919876543210');
    expect(rows[1]).not.toHaveTextContent('+91');
  });

  it('adds a guest without an account and explains a duplicate phone', async () => {
    const user = userEvent.setup();
    api.createGuest.mockResolvedValueOnce(PLAYER).mockRejectedValueOnce(apiError(409, 'DUPLICATE_PLAYER_PHONE', 'dup'));
    renderPage();
    await screen.findAllByTestId('player-row');
    await user.type(screen.getByLabelText('Name'), 'Dev');
    await user.type(screen.getByLabelText(/phone \(optional\)/i), '9876543210');
    await user.click(screen.getByTestId('add-player'));
    await waitFor(() => expect(api.createGuest).toHaveBeenCalledWith({ name: 'Dev', phone: '9876543210', role: undefined }));

    await user.type(screen.getByLabelText('Name'), 'Dev again');
    await user.type(screen.getByLabelText(/phone \(optional\)/i), '9876543210');
    await user.click(screen.getByTestId('add-player'));
    expect(await screen.findByTestId('players-message')).toHaveTextContent(/already have a player with that phone number/i);
  });

  it('creates my own profile once and then shows it', async () => {
    const user = userEvent.setup();
    api.createOwn.mockResolvedValue(OWN);
    api.mine.mockReset();
    api.mine.mockResolvedValueOnce([PLAYER, PLAYER_2]).mockResolvedValue([OWN, PLAYER]);
    renderPage();
    await user.type(await screen.findByLabelText(/your player name/i), 'Me Myself');
    await user.click(screen.getByTestId('create-own'));
    await waitFor(() => expect(api.createOwn).toHaveBeenCalledWith({ name: 'Me Myself' }));

    await waitFor(() => expect(screen.getByTestId('own-profile')).toHaveTextContent('Me Myself'));
  });

  it('finds guest profiles by phone, shows only a shortened name, and asks to claim one', async () => {
    const user = userEvent.setup();
    api.claimable.mockResolvedValue([{ playerId: 'p-9', displayName: 'Arjun K.' }]);
    api.claim.mockResolvedValue({ ...CLAIM, playerId: 'p-9' });
    renderPage();
    await user.type(await screen.findByLabelText(/your phone number/i), '98765 43210');
    await user.click(screen.getByTestId('find-claimable'));

    const row = await screen.findByTestId('claimable-row');
    expect(row).toHaveTextContent('Arjun K.');
    await user.click(within(row).getByTestId('claim-player'));

    await waitFor(() => expect(api.claim).toHaveBeenCalledWith('p-9'));
    expect(await screen.findByTestId('players-message')).toHaveTextContent(/has to approve it/i);
  });

  it('says when nobody added that number and explains a refused claim', async () => {
    const user = userEvent.setup();
    api.claimable.mockResolvedValueOnce([]).mockResolvedValueOnce([{ playerId: 'p-9', displayName: 'Arjun K.' }]);
    api.claim.mockRejectedValue(apiError(409, 'CLAIMANT_ALREADY_LINKED', 'x'));
    renderPage();
    await user.type(await screen.findByLabelText(/your phone number/i), '9000000000');
    await user.click(screen.getByTestId('find-claimable'));
    expect(await screen.findByTestId('no-claimable')).toBeInTheDocument();

    await user.click(screen.getByTestId('find-claimable'));
    await user.click(await screen.findByTestId('claim-player'));
    expect(await screen.findByTestId('players-message')).toHaveTextContent(/two profiles cannot be merged yet/i);
  });

  it('does not offer claiming to someone who already has their own profile', async () => {
    api.mine.mockResolvedValue([OWN]);
    renderPage();
    await screen.findByTestId('own-profile');

    expect(screen.queryByTestId('find-claimable')).toBeNull();
  });

  it('shows who is asking and approves or rejects a claim on a guest I added', async () => {
    const user = userEvent.setup();
    api.incomingClaims.mockResolvedValue([CLAIM, { ...CLAIM, id: 'c-2', claimantName: 'Other Person', claimantEmail: 'other@example.com' }]);
    api.approveClaim.mockResolvedValue({ ...CLAIM, status: 'APPROVED' });
    api.rejectClaim.mockResolvedValue({ ...CLAIM, id: 'c-2', status: 'REJECTED' });
    renderPage();

    const rows = await screen.findAllByTestId('incoming-claim');
    expect(rows[0]).toHaveTextContent('Arjun Kumar (arjun@example.com) says they are Arjun Kumar');
    await user.click(within(rows[0]).getByTestId('approve-claim'));
    await waitFor(() => expect(api.approveClaim).toHaveBeenCalledWith('c-1'));
    await user.click(within(rows[1]).getByTestId('reject-claim'));
    await waitFor(() => expect(api.rejectClaim).toHaveBeenCalledWith('c-2'));
  });

  it('shows what became of my own claims', async () => {
    api.myClaims.mockResolvedValue([{ ...CLAIM, claimantName: null, claimantEmail: null }, { ...CLAIM, id: 'c-3', status: 'REJECTED', claimantName: null, claimantEmail: null }]);
    renderPage();

    const box = await screen.findByTestId('my-claims');
    expect(box).toHaveTextContent('Waiting for the person who added them to agree');
    expect(box).toHaveTextContent('Not approved');
  });
});

function renderPage() {
  return renderWithProviders(<PlayersPage />);
}
