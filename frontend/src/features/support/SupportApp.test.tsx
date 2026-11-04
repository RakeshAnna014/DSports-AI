import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { pick, renderWithProviders } from '@/test/render';
import SupportApp from './SupportApp';
import { supportApi } from '@/api/support';
import { supportStaffAi } from '@/api/supportStaffAi';
import { useAuthStore } from '@/store/authStore';
import type { StaffCase, SupportAnalytics } from '@/types/support';

vi.mock('@/api/support', async (orig) => {
  const real = await orig<typeof import('@/api/support')>();
  return { ...real, supportApi: { ...real.supportApi, staff: { list: vi.fn(), get: vi.fn(), analytics: vi.fn(), priority: vi.fn(), assign: vi.fn(), note: vi.fn(), reply: vi.fn(), status: vi.fn(), resolve: vi.fn() } } };
});

vi.mock('@/api/supportStaffAi', () => ({ supportStaffAi: { report: vi.fn(), insights: vi.fn(), assist: vi.fn(), record: vi.fn() } }));

const EMPTY_REPORT = { range: 'last7', from: '2026-10-09', to: '2026-10-15', timeZone: 'UTC', createdInPeriod: 0, resolvedInPeriod: 0, reopenedInPeriod: 0, escalatedInPeriod: 0, createdInPeriodStillUnresolved: 0,
  openNow: 0, inProgressNow: 0, unresolvedNow: 0, unassignedNow: 0, escalationPendingNow: 0, createdByCategory: {}, createdByPriority: {}, daily: [], oldestUnresolvedAgeHours: null, slaNote: '', definitions: {} };
const EMPTY_INSIGHTS = { period: '', items: [], recommendations: [], limitations: [], chatbot: { conversations_with_support_turns: 0, linked_to_a_case: 0, feedback: {}, resolution_rate: null, note: 'none' } };

const ANALYTICS: SupportAnalytics = {
  totalCases: 23, unresolvedCases: 14, byStatus: { OPEN: 6, INVESTIGATING: 5, WAITING_FOR_CUSTOMER: 3, RESOLVED: 9 }, byCategory: { PAYMENT_ISSUE: 4, TECHNICAL_ISSUE: 2 },
  unresolvedByPriority: { HIGH: 3, LOW: 11 }, escalationRequestedNotYetAccepted: 2, escalationAccepted: 1, unassignedUnresolved: 7, resolvedByOutcome: {}, resolvedByHuman: 9,
  automaticallyResolved: 0, reopenedCases: 1, casesWithRepeatContact: 0, averageUnresolvedAgeHours: 5, oldestUnresolvedAgeHours: 30, createdByAssistant: 5, createdByWeb: 18,
  definitions: { automaticallyResolved: 'Always 0 in this POC.' },
};
const ROW = { id: 'c1', caseNumber: 'CASE-20261013-000001', category: 'PAYMENT_ISSUE', status: 'OPEN' as const, priority: 'HIGH' as const, subject: 'Charged twice', orderNumber: null,
  escalation: { state: 'NONE' as const, reason: null, humanAccepted: false }, createdAt: '2026-10-13T00:00:00Z', updatedAt: '2026-10-13T00:00:00Z' };

const asStaff = () => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'staff-1', email: 's@example.com', roles: ['SUPPORT_EXECUTIVE'] }, hydrate: () => {} });
const mount = (route = '/support-cases') => renderWithProviders(<Routes><Route path="/support-cases/:id?" element={<SupportApp />} /></Routes>, route);

describe('Support console', () => {
  beforeEach(() => {
    asStaff();
    vi.mocked(supportStaffAi.report).mockResolvedValue(EMPTY_REPORT);
    vi.mocked(supportStaffAi.insights).mockResolvedValue(EMPTY_INSIGHTS);
    vi.mocked(supportApi.staff.analytics).mockResolvedValue(ANALYTICS);
    vi.mocked(supportApi.staff.list).mockResolvedValue({ items: [ROW], total: 60, page: 0, size: 25 });
  });

  it('is restricted for a customer and never calls the staff APIs', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'c@example.com', roles: ['CUSTOMER'] }, hydrate: () => {} });
    mount();
    expect(await screen.findByTestId('staff-restricted')).toBeInTheDocument();
    expect(supportApi.staff.list).not.toHaveBeenCalled();
    expect(supportApi.staff.analytics).not.toHaveBeenCalled();
  });

  it('shows the metrics exactly as the backend counted them, with no invented SLA or overdue figure', async () => {
    mount();
    expect(await screen.findByTestId('kpi-open')).toHaveTextContent('6');
    expect(screen.getByTestId('kpi-in-progress')).toHaveTextContent('8');           // investigating 5 + waiting for customer 3
    expect(screen.getByTestId('kpi-resolved')).toHaveTextContent('9');
    expect(screen.getByTestId('kpi-unassigned')).toHaveTextContent('7');
    const breakdown = screen.getByTestId('analytics-breakdown');
    expect(breakdown).toHaveTextContent('Payment issue: 4');
    expect(breakdown).toHaveTextContent('HIGH (unresolved): 3');
    expect(screen.queryByText(/overdue/i)).not.toBeInTheDocument();
  });

  it('sends the chosen filters to the backend and returns to the first page', async () => {
    const user = userEvent.setup();
    mount();
    await screen.findByTestId('staff-case-row');
    await pick(user, /category/i, 'Technical issue');
    fireEvent.change(screen.getByTestId('filter-from'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByTestId('filter-to'), { target: { value: '2026-10-13' } });
    await user.type(screen.getByTestId('filter-q'), 'CASE-2026');
    await user.click(screen.getByTestId('filter-mine'));
    await waitFor(() => {
      const calls = vi.mocked(supportApi.staff.list).mock.calls;
      const last = calls[calls.length - 1][0];
      expect(last).toMatchObject({ category: 'TECHNICAL_ISSUE', createdFrom: '2026-10-01', createdTo: '2026-10-13', q: 'CASE-2026', assignedTo: 'staff-1', page: 0 });
    });
  });

  it('is paginated: the next page is requested from the server, not sliced locally', async () => {
    const user = userEvent.setup();
    mount();
    await screen.findByTestId('staff-case-row');
    expect(screen.getByTestId('staff-pagination')).toHaveTextContent('1-25 of 60');
    await user.click(screen.getByRole('button', { name: /next page/i }));
    await waitFor(() => {
      const calls = vi.mocked(supportApi.staff.list).mock.calls;
      expect(calls[calls.length - 1][0]).toMatchObject({ page: 1, size: 25 });
    });
  });

  it('shows an honest empty state and an error state', async () => {
    vi.mocked(supportApi.staff.list).mockResolvedValue({ items: [], total: 0, page: 0, size: 25 });
    const { unmount } = mount();
    expect(await screen.findByTestId('staff-empty')).toHaveTextContent('No cases match');
    unmount();
    vi.mocked(supportApi.staff.list).mockRejectedValue({ isAxiosError: true, response: { status: 500, data: { message: 'boom' } } });
    mount();
    expect(await screen.findByTestId('staff-list-error')).toBeInTheDocument();
  });

  it('lets staff change the priority only with a reason and sends the version they were looking at', async () => {
    const user = userEvent.setup();
    const detail = { ...ROW, customerId: 'cust-1', priorityReasons: ['Base priority MEDIUM'], description: 'x', evidence: [], policyCitations: [], missingInformation: [], safetyRelated: false, source: 'WEB',
      reopenCount: 0, version: 4, messages: [], events: [] } as unknown as StaffCase;
    vi.mocked(supportApi.staff.get).mockResolvedValue(detail);
    vi.mocked(supportApi.staff.priority).mockResolvedValue({ ...detail, priority: 'URGENT' });
    mount('/support-cases/CASE-20261013-000001');
    await screen.findByTestId('staff-detail');
    const apply = screen.getByTestId('staff-change-priority');
    expect(apply).toBeDisabled();
    await pick(user, /change priority/i, 'URGENT');
    expect(apply).toBeDisabled();                                                   // a reason is required
    await user.type(screen.getByTestId('staff-priority-reason'), 'Customer travelling');
    await user.click(apply);
    await waitFor(() => expect(supportApi.staff.priority).toHaveBeenCalledWith('c1', 'URGENT', 'Customer travelling', 4));
  });
});
