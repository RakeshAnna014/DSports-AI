import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import Header from './Header';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/features/notifications/NotificationCenter', () => ({ default: () => null }));

const Where = () => <div data-testid="where">{useLocation().pathname}</div>;

const renderHeader = () => renderWithProviders(
  <>
    <Header />
    <Routes><Route path="*" element={<Where />} /></Routes>
  </>);

const signIn = (...roles: string[]) => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER', ...roles] } });

describe('Header navigation for the venue features', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: false, user: null });
  });

  it('offers courts to everyone and nothing role-specific to a plain customer', () => {
    signIn();
    renderHeader();

    expect(screen.getByRole('link', { name: 'Courts' })).toHaveAttribute('href', '/venues');
    expect(screen.queryByTestId('nav-admin')).toBeNull();
    expect(screen.queryByTestId('nav-owner-console')).toBeNull();
  });

  it('shows the Owner console only to a ground owner', () => {
    signIn('GROUND_OWNER');
    renderHeader();

    expect(screen.getByTestId('nav-owner-console')).toHaveAttribute('href', '/owner/venues');
    expect(screen.queryByTestId('nav-admin')).toBeNull();
  });

  it('shows an Admin menu with ground owners, venues and sports to an administrator, and each item goes to its screen', async () => {
    const user = userEvent.setup();
    signIn('ADMIN');
    renderHeader();

    expect(screen.queryByTestId('nav-owner-console')).toBeNull();
    await user.click(screen.getByTestId('nav-admin'));
    expect(screen.getByTestId('admin-ground-owners')).toHaveTextContent('Ground owners');
    expect(screen.getByTestId('admin-venues')).toHaveTextContent('Venues');
    // The administrator's other consoles live in the same menu, so the top bar stays on one row.
    for (const id of ['nav-inventory-ai', 'nav-engineering-ai', 'nav-fulfilment', 'nav-support-console']) expect(screen.getByTestId(id)).toBeInTheDocument();
    await user.click(screen.getByTestId('admin-sports'));

    expect(await screen.findByTestId('where')).toHaveTextContent('/admin/sports');
  });

  it('keeps the Support console as a button for support staff who are not administrators, and out of the top bar for administrators', () => {
    signIn('SUPPORT_EXECUTIVE');
    const first = renderHeader();
    expect(screen.getByTestId('nav-support-console')).toHaveAttribute('href', '/support-cases');
    expect(screen.queryByTestId('nav-admin')).toBeNull();
    first.unmount();

    signIn('ADMIN');
    renderHeader();
    expect(screen.queryByTestId('nav-support-console')).toBeNull();
  });

  it('shows neither to a visitor who is signed out', () => {
    renderHeader();

    expect(screen.queryByTestId('nav-admin')).toBeNull();
    expect(screen.queryByTestId('nav-owner-console')).toBeNull();
  });
});
