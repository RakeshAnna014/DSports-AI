import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import VenuesPage from './VenuesPage';
import { venuesApi } from '@/api/venues';
import { BADMINTON, CRICKET, FOOTBALL, VENUE } from '@/test/venueFixtures';
import type { Sport, VenueSummary } from '@/types/venues';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, venuesApi: { sports: vi.fn(), search: vi.fn() } };
});

const SPORTS: Sport[] = [CRICKET, FOOTBALL, BADMINTON].map((s) => ({ ...s, description: null }));
const HIT: VenueSummary = {
  venue: VENUE,
  courts: [{ courtId: 'c-1', name: 'Court 1', availableSlots: 12, lowestAvailablePrice: 1000 }, { courtId: 'c-2', name: 'Hall', availableSlots: 3, lowestAvailablePrice: 600 }],
  availableSlots: 15, lowestAvailablePrice: 600,
};

const lastSearch = () => { const calls = vi.mocked(venuesApi.search).mock.calls; return calls[calls.length - 1]?.[0]; };

const Where = () => <div data-testid="where">{useLocation().pathname + useLocation().search}</div>;

const renderPage = (url = '/venues') => renderWithProviders(
  <Routes>
    <Route path="/venues" element={<VenuesPage />} />
    <Route path="/venues/:id" element={<Where />} />
  </Routes>, url);

describe('VenuesPage', () => {
  beforeEach(() => {
    vi.mocked(venuesApi.sports).mockResolvedValue(SPORTS);
    vi.mocked(venuesApi.search).mockResolvedValue([HIT]);
  });

  it('asks for a sport first and offers only the bookable sports', async () => {
    renderPage();

    const chips = await screen.findAllByTestId('sport-chip');
    expect(chips.map((c) => c.textContent)).toEqual(['Cricket', 'Football', 'Badminton']);
    expect(screen.getByText(/choose a sport to begin/i)).toBeInTheDocument();
    expect(screen.getByTestId('venues-search')).toBeDisabled();
    expect(venuesApi.search).not.toHaveBeenCalled();
  });

  it('searches venues for the chosen sport by its id, and keeps the choice in the address', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click((await screen.findAllByTestId('sport-chip'))[2]);

    const cards = await screen.findAllByTestId('venue-card');
    expect(cards).toHaveLength(1);
    expect(cards[0]).toHaveTextContent('Green Park Sports Hub');
    expect(cards[0]).toHaveTextContent('Court 1 · 12 free');
    expect(cards[0]).toHaveTextContent('Hall · 3 free');
    expect(cards[0]).toHaveTextContent(/from .*600/);
    expect(cards[0]).toHaveTextContent('15 slots free');
    expect(lastSearch()).toMatchObject({ sport: 's-badminton' });
    expect(screen.getAllByTestId('sport-chip')[2]).toHaveAttribute('aria-pressed', 'true');
  });

  it('passes the city and the date, and a city typed in any case is sent as typed (the server matches it ignoring case)', async () => {
    const user = userEvent.setup();
    renderPage('/venues?sport=cricket');
    await screen.findAllByTestId('venue-card');

    await user.type(screen.getByLabelText('City'), 'BENGALURU');
    await user.click(screen.getByTestId('venues-search'));

    await waitFor(() => expect(lastSearch()).toMatchObject({ sport: 's-cricket', city: 'BENGALURU' }));
  });

  it('opens a venue with the sport and day carried over', async () => {
    const user = userEvent.setup();
    renderPage('/venues?sport=cricket&date=2026-11-21');

    await user.click(within(await screen.findByTestId('venue-card')).getByRole('button'));

    expect(await screen.findByTestId('where')).toHaveTextContent('/venues/v-1?sport=cricket&date=2026-11-21');
  });

  it('says so when no venue has a free slot, and when the list cannot load', async () => {
    vi.mocked(venuesApi.search).mockResolvedValueOnce([]);
    renderPage('/venues?sport=football');
    expect(await screen.findByText(/no football courts with free slots/i)).toBeInTheDocument();
  });

  it('shows an error with a retry when the sports cannot be loaded', async () => {
    vi.mocked(venuesApi.sports).mockRejectedValueOnce(new Error('down'));
    renderPage();

    expect(await screen.findByText(/couldn't load the sports/i)).toBeInTheDocument();
  });
});
