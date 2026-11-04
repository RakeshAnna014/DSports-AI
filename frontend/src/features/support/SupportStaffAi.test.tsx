import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import ReportPanel from './ReportPanel';
import AiWorkspace from './AiWorkspace';
import { supportApi } from '@/api/support';
import { supportStaffAi, type StaffPlan, type SupportReport } from '@/api/supportStaffAi';

vi.mock('@/api/support', async (orig) => {
  const real = await orig<typeof import('@/api/support')>();
  return { ...real, supportApi: { ...real.supportApi, staff: { assign: vi.fn(), note: vi.fn(), reply: vi.fn(), status: vi.fn(), resolve: vi.fn() } } };
});
vi.mock('@/api/supportStaffAi', () => ({ supportStaffAi: { report: vi.fn(), insights: vi.fn(), assist: vi.fn(), record: vi.fn() } }));

const REF = 'CASE-20261010-000042';
const REPORT: SupportReport = {
  range: 'last7', from: '2026-10-09', to: '2026-10-15', timeZone: 'UTC', createdInPeriod: 5, resolvedInPeriod: 2, reopenedInPeriod: 1, escalatedInPeriod: 1, createdInPeriodStillUnresolved: 3,
  openNow: 4, inProgressNow: 2, unresolvedNow: 6, unassignedNow: 1, escalationPendingNow: 1, createdByCategory: { PAYMENT_ISSUE: 3 }, createdByPriority: { HIGH: 2 },
  daily: [{ date: '2026-10-14', created: 3, resolved: 1 }, { date: '2026-10-15', created: 2, resolved: 1 }], oldestUnresolvedAgeHours: 12, slaNote: 'No SLA is defined.', definitions: {},
};
const PLAN: StaffPlan = {
  caseRef: REF, generatedAt: '2026-10-15T10:00:00Z', category: 'MISSING_DELIVERY', playbook: 'Order shown as delivered but not received', status: 'OPEN', version: 3, problem: 'Parcel missing (category MISSING_DELIVERY).',
  known: [{ statement: 'Ticket is OPEN, priority HIGH.', source: 'TICKET', ref: REF }], uncertain: ['Did a neighbour take it?'],
  likelyCause: { status: 'UNCONFIRMED', hypotheses: [{ status: 'HYPOTHESIS', statement: 'May have been left with a neighbour.', confidence: 'MEDIUM', basis: 'recorded as delivered', to_confirm: 'courier proof of delivery' }] },
  evidence: [], steps: [{ order: 1, text: 'Read the tracking history.' }], tools: [], verification: ['The shipment record was read.'],
  customerDraft: { text: 'Hello,\n\nWe are checking.', basis: ['shipment status DELIVERED'], never_includes: 'internal notes', review_required: true },
  escalation: { conditions: ['High-value order.'], recommended: true, reason: 'The customer asked for a person and nobody has accepted yet.' },
  live: { available: false, reason: 'Live order data can only be read by an administrator.' }, knowledge: { available: false, sources: [] }, engineering: { available: false }, limitations: ['Advice only.'], history: [], sensitive: false,
  actions: [
    { id: 'a-reply', kind: 'REPLY_CUSTOMER', label: 'Send the drafted reply', operation: 'POST /x', body: { body: 'Hello,\n\nWe are checking.', expectedVersion: 3 }, confirmation: 'The customer will see this.', state: 'SUGGESTED', editable_text: true, draft_basis: ['shipment status DELIVERED'] },
    { id: 'a-resolve', kind: 'RESOLVE', label: 'Resolve with an outcome (you decide)', operation: 'POST /y', body: { outcome: null, note: '', evidenceRefs: [], expectedVersion: 3 }, confirmation: 'Resolving closes the ticket.', state: 'SUGGESTED',
      outcomes: ['ANSWERED', 'DELIVERY_CONFIRMED'], must_verify: ['The shipment record was read.'], blocked_reason: 'You verify and decide.' },
  ],
};

describe('Support report panel', () => {
  beforeEach(() => {
    vi.mocked(supportStaffAi.report).mockResolvedValue(REPORT);
    vi.mocked(supportStaffAi.insights).mockResolvedValue({ period: '', items: [{ label: 'OBSERVATION', text: '3 of 5 tickets are Payment Issue.', denominator: '3 of 5 tickets' }, { label: 'HYPOTHESIS', text: 'Maybe a payment problem.', denominator: '3 tickets', to_confirm: 'engineering signal' }],
      recommendations: [], limitations: ['Nothing is estimated.'], chatbot: { conversations_with_support_turns: 4, linked_to_a_case: 1, feedback: {}, resolution_rate: null, note: 'No customer feedback was recorded, so a resolution rate cannot be stated.' } });
  });

  it('shows period metrics, the still-unresolved cohort separately from now, and the labelled insights', async () => {
    renderWithProviders(<ReportPanel />);
    expect(await screen.findByTestId('report-created')).toHaveTextContent('5');
    expect(screen.getByTestId('report-cohort')).toHaveTextContent('3');
    expect(screen.getByTestId('report-now')).toHaveTextContent('6');
    expect(screen.getAllByTestId('trend-day')).toHaveLength(2);
    const items = await screen.findAllByTestId('insight-item');
    expect(items[0]).toHaveTextContent('OBSERVATION');
    expect(items[1]).toHaveTextContent('HYPOTHESIS');
    expect(screen.getByTestId('insight-chatbot')).toHaveTextContent('cannot be stated');
  });

  it('sends the chosen period to the backend and waits for a valid custom range', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReportPanel />);
    await screen.findByTestId('report-created');
    await user.click(screen.getByTestId('range-yesterday'));
    await waitFor(() => expect(supportStaffAi.report).toHaveBeenLastCalledWith(expect.objectContaining({ range: 'yesterday' })));
    const calls = vi.mocked(supportStaffAi.report).mock.calls.length;
    await user.click(screen.getByTestId('range-custom'));
    expect(screen.getByTestId('report-custom-hint')).toBeInTheDocument();
    expect(vi.mocked(supportStaffAi.report).mock.calls.length).toBe(calls);            // no request until both dates are valid
    fireEvent.change(screen.getByTestId('report-from'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByTestId('report-to'), { target: { value: '2026-10-07' } });
    await waitFor(() => expect(supportStaffAi.report).toHaveBeenLastCalledWith({ range: 'custom', from: '2026-10-01', to: '2026-10-07' }));
  });

  it('says plainly when nothing was created', async () => {
    vi.mocked(supportStaffAi.report).mockResolvedValue({ ...REPORT, createdInPeriod: 0, createdByCategory: {}, daily: [] });
    renderWithProviders(<ReportPanel />);
    expect(await screen.findByTestId('report-empty')).toBeInTheDocument();
  });
});

describe('Per-ticket AI workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(supportStaffAi.assist).mockResolvedValue(PLAN);
    vi.mocked(supportStaffAi.record).mockResolvedValue([]);
  });

  it('shows a plan with verified facts, labelled hypotheses and an escalation recommendation', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AiWorkspace reference={REF} />);
    await user.click(screen.getByTestId('ai-plan-open'));
    expect(await screen.findByTestId('ai-plan')).toHaveAttribute('data-category', 'MISSING_DELIVERY');
    expect(within(screen.getByTestId('ai-causes')).getByText(/HYPOTHESIS/)).toBeInTheDocument();
    expect(screen.getByTestId('ai-live-note')).toHaveTextContent('administrator');
    expect(screen.getByTestId('ai-escalation')).toHaveTextContent('asked for a person');
  });

  it('records attempted then succeeded only after the backend accepts, and confirms first', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.staff.reply).mockResolvedValue({} as never);
    renderWithProviders(<AiWorkspace reference={REF} />);
    await user.click(screen.getByTestId('ai-plan-open'));
    const card = (await screen.findAllByTestId('ai-action')).find((c) => c.dataset.kind === 'REPLY_CUSTOMER')!;
    await user.click(within(card).getByTestId('ai-action-run'));
    expect(supportApi.staff.reply).not.toHaveBeenCalled();                              // nothing happens before the confirmation
    await user.click(await screen.findByTestId('ai-confirm-yes'));
    await waitFor(() => expect(supportApi.staff.reply).toHaveBeenCalledWith(REF, 'Hello,\n\nWe are checking.', 3));
    const states = vi.mocked(supportStaffAi.record).mock.calls.map((c) => c[2]);
    expect(states).toEqual(['ATTEMPTED', 'SUCCEEDED']);
  });

  it('records FAILED and reports the backend refusal instead of claiming success', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.staff.reply).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'OPTIMISTIC_LOCKING_CONFLICT', message: 'changed' } } });
    renderWithProviders(<AiWorkspace reference={REF} />);
    await user.click(screen.getByTestId('ai-plan-open'));
    const card = (await screen.findAllByTestId('ai-action')).find((c) => c.dataset.kind === 'REPLY_CUSTOMER')!;
    await user.click(within(card).getByTestId('ai-action-run'));
    await user.click(await screen.findByTestId('ai-confirm-yes'));
    await waitFor(() => expect(supportStaffAi.record).toHaveBeenCalledWith(REF, 'a-reply', 'FAILED', expect.anything()));
    expect(vi.mocked(supportStaffAi.record).mock.calls.map((c) => c[2])).not.toContain('SUCCEEDED');
  });

  it('keeps Resolve disabled until the outcome, a note and every verification are provided by the person', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AiWorkspace reference={REF} />);
    await user.click(screen.getByTestId('ai-plan-open'));
    const card = (await screen.findAllByTestId('ai-action')).find((c) => c.dataset.kind === 'RESOLVE')!;
    const run = within(card).getByTestId('ai-action-run');
    expect(run).toBeDisabled();
    await user.type(within(card).getByTestId('ai-resolve-note'), 'Courier confirmed.');
    await user.click(within(card).getByTestId('ai-verify'));
    expect(run).toBeDisabled();                                                          // still no outcome: the assistant never picks one
    expect(within(card).queryByText(/Delivery confirmed/i)).not.toBeInTheDocument();
  });
});
