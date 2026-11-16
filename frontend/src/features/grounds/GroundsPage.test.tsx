import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import GroundsPage from './GroundsPage';
import { groundsApi } from '@/api/grounds';
import type { Ground } from '@/types/grounds';

vi.mock('@/api/grounds', async (orig) => {
  const real = await orig<typeof import('@/api/grounds')>();
  return { ...real, groundsApi: { search: vi.fn() } };
});

const GROUND: Ground = {
  id: 'g-1', name: 'Green Park Turf', address: '12 Park Road', city: 'Bengaluru', latitude: null, longitude: null, pitchType: 'TURF', facilities: ['Floodlights'], photoUrls: [],
  opensAt: '06:00:00', closesAt: '22:00:00', timeZone: 'Asia/Kolkata', slotMinutes: 60, basePrice: 1000, weekendPrice: null, peakPrice: null, peakFrom: null, peakTo: null,
  currency: 'INR', active: true,
};

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/" element={<GroundsPage />} />
    <Route path="/grounds/:id" element={<div>GROUND PAGE</div>} />
  </Routes>);

describe('GroundsPage', () => {
  beforeEach(() => {
    vi.mocked(groundsApi.search).mockResolvedValue([{ ground: GROUND, availableSlots: 7, lowestAvailablePrice: 1000 }]);
  });

  it('lists grounds with their cheapest free slot and how many are free on the chosen day', async () => {
    renderPage();
    const card = await screen.findByTestId('ground-card');

    expect(card).toHaveTextContent('Green Park Turf');
    expect(card).toHaveTextContent('Bengaluru');
    expect(card).toHaveTextContent(/from .*1,000/);
    expect(card).toHaveTextContent(/7 slots free/);
  });

  it('searches by city and date, trimming the city', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('ground-card');
    await user.type(screen.getByLabelText(/city/i), '  Pune ');
    await user.click(screen.getByTestId('grounds-search'));

    await waitFor(() => expect(groundsApi.search).toHaveBeenLastCalledWith({ city: 'Pune', date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) }));
  });

  it('says so when nothing is free, and opens a ground on click', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(within(await screen.findByTestId('ground-card')).getByRole('button'));
    expect(await screen.findByText('GROUND PAGE')).toBeInTheDocument();

    vi.mocked(groundsApi.search).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText(/no grounds with free slots/i)).toBeInTheDocument();
  });
});
