import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { expect } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

/** Renders with the providers the app uses: a fresh query cache (no retries, no sharing between tests) and a router. */
export const renderWithProviders = (ui: ReactElement, route = '/') => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return { client, ...render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter></QueryClientProvider>) };
};

/** Chooses an option of a MUI select by its accessible name, then waits for the menu to finish closing (it blocks pointer events while it does). */
export const pick = async (user: UserEvent, name: RegExp, option: string | RegExp) => {
  await user.click(screen.getByRole('combobox', { name }));
  await user.click(within(await screen.findByRole('listbox')).getByText(option));
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
};
