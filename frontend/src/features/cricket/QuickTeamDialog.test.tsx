import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import { parseNames, QuickTeamDialog } from './QuickTeamDialog';
import { cricketApi } from '@/api/cricket';
import { PLAYER, TEAM } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({ cricketApi: { teams: { create: vi.fn(), addMember: vi.fn() }, players: { createGuest: vi.fn() } } }));
const api = vi.mocked(cricketApi, true);

describe('parseNames', () => {
  it('trims, drops blanks and repeats in any case, and caps at 30', () => {
    expect(parseNames('  Arjun  Kumar \n\nbala\nARJUN KUMAR\n')).toEqual(['Arjun Kumar', 'bala']);
    expect(parseNames(Array.from({ length: 40 }, (_, i) => `P${i}`).join('\n'))).toHaveLength(30);
  });
});

describe('QuickTeamDialog', () => {
  const onCreated = vi.fn();
  const onClose = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks();
    api.teams.create.mockResolvedValue({ ...TEAM, id: 'new', name: 'Rovers', members: [] });
    api.teams.addMember.mockResolvedValue({ ...TEAM, id: 'new', name: 'Rovers' });
    api.players.createGuest.mockImplementation(async (input) => ({ ...PLAYER, id: `g-${input.name}`, name: input.name }));
  });
  const open = () => renderWithProviders(<QuickTeamDialog open onClose={onClose} onCreated={onCreated} />);

  it('creates the team and one guest player per name, then hands the team back', async () => {
    const user = userEvent.setup();
    open();
    await user.type(screen.getByLabelText('Team name'), 'Rovers');
    await user.type(screen.getByLabelText(/players \(one name per line\)/i), 'Arjun{Enter}Bala{Enter}{Enter}arjun');
    expect(screen.getByTestId('quick-team-count')).toHaveTextContent('2 players');
    await user.click(screen.getByTestId('quick-team-create'));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(api.teams.create).toHaveBeenCalledWith('Rovers');
    expect(api.players.createGuest.mock.calls.map((c) => c[0])).toEqual([{ name: 'Arjun' }, { name: 'Bala' }]);
    expect(api.teams.addMember).toHaveBeenCalledWith('new', 'g-Arjun');
    expect(api.teams.addMember).toHaveBeenCalledWith('new', 'g-Bala');
    expect(onClose).toHaveBeenCalled();
  });

  it('keeps the team, lists the names it could not add and does not allow creating it twice', async () => {
    const user = userEvent.setup();
    api.players.createGuest.mockImplementation(async (input) => {
      if (input.name === 'Bala') throw new Error('boom');
      return { ...PLAYER, id: `g-${input.name}`, name: input.name };
    });
    open();
    await user.type(screen.getByLabelText('Team name'), 'Rovers');
    await user.type(screen.getByLabelText(/players \(one name per line\)/i), 'Arjun{Enter}Bala');
    await user.click(screen.getByTestId('quick-team-create'));

    expect(await screen.findByTestId('quick-team-error')).toHaveTextContent('Rovers was created, but these players could not be added: Bala');
    expect(onCreated).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('Team name'), 'Rovers again');
    expect(screen.getByTestId('quick-team-create')).toBeDisabled();
  });

  it('needs a team name and shows a refusal of the team itself', async () => {
    const user = userEvent.setup();
    api.teams.create.mockRejectedValue({ response: { status: 409, data: { code: 'TEAM_NAME_TAKEN', message: 'You already have a team with that name' } }, isAxiosError: true });
    open();
    expect(screen.getByTestId('quick-team-create')).toBeDisabled();
    await user.type(screen.getByLabelText('Team name'), 'Lions');
    await user.click(screen.getByTestId('quick-team-create'));

    expect(await screen.findByTestId('quick-team-error')).toBeInTheDocument();
    expect(api.players.createGuest).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
  });
});
