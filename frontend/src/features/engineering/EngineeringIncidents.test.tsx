import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { renderWithProviders } from '@/test/render';
import EngineeringApp from './EngineeringApp';
import { engineeringApi, type EngIncident, type EngIncidentPage, type EngIncidentReport, type EngLogPage, type EngSourceStatus } from '@/api/engineeringAi';
import { rangeQuery } from './TimeRange';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/engineeringAi', () => ({
  engineeringApi: { investigate: vi.fn(), sources: vi.fn(), sourceStatus: vi.fn(), refresh: vi.fn(), logs: vi.fn(), incidents: vi.fn(), investigateIncident: vi.fn(), incidentPrompt: vi.fn() },
}));

const STATUS: EngSourceStatus = {
  server_time: '2026-10-15T10:00:00Z', live_threshold_seconds: 120, delayed_threshold_seconds: 900,
  sources: [{ service: 'backend', origin: 'real', source: 'file:x', records: 10, first_ts: '2026-10-15T08:00:00Z', last_ts: '2026-10-15T09:59:50Z', lag_seconds: 3600, freshness: 'STALE' }],
};
const inc = (n: number, over: Partial<EngIncident> = {}): EngIncident => ({
  reference: `SIG-0000000${n}`, signature: `sig${n}`.padEnd(12, '0'), title: `TimeoutException: Pool acquire timed out ${n}`, level: 'ERROR', category: 'DATABASE', services: ['backend'], count: 100 - n,
  first_seen: '2026-10-15T08:00:00Z', last_seen: '2026-10-15T09:50:00Z', first_seen_overall: '2026-10-15T08:00:00Z', previous_window_count: 0, change: 'NEW_IN_WINDOW', status: 'NEW', score: 90 - n,
  score_breakdown: { severity: 40, recency: 19, trend: 20, volume: 9, impact: 2 }, endpoints: ['/api/v1/payments/{id}/capture'], correlation_ids: ['aaaaaaaa-1111-2222-3333-444444444444'],
  impact: { affected_requests_estimate: 12, http_5xx_records: 5, label: 'ESTIMATE from distinct correlation ids' }, evidence_ids: [11, 12], evidence: [], origin: 'real',
  investigation: { status: 'NOT_INVESTIGATED', at: null }, ...over,
});
const page = (items: EngIncident[], over: Partial<EngIncidentPage> = {}): EngIncidentPage => ({
  window: { since: '2026-10-14T10:00:00Z', until: '2026-10-15T10:00:00Z' }, total: items.length, offset: 0, limit: 10, has_more: false, incidents: items, ranking: 'score = severity + recency + trend', note: 'Incident groups are derived from log signatures.',
  data_freshness: [], ...over,
});
const REPORT: EngIncidentReport = {
  reference: 'SIG-00000001', signature: 'sig1'.padEnd(12, '0'), window: { since: '2026-10-14T10:00:00Z', until: '2026-10-15T10:00:00Z' }, generated_at: '2026-10-15T10:00:00Z', origin: 'real',
  summary: '99 x [ERROR] Pool acquire timed out', observed_evidence: [{ id: 11, ts: '2026-10-15T08:00:00Z', service: 'backend', origin: 'real', level: 'ERROR', message: 'Pool acquire timed out', injection_flagged: false }], endpoints: ['/api/v1/payments/{id}/capture'],
  before_after: { errors_2h_before_first_seen: 0, errors_2h_after_first_seen: 99, other_error_groups_within_30_min: [] },
  cross_service: { traced_requests: [], first_failing_service_counts: {} }, dependency_errors: { co_occurring_groups: [], postgres_error_records_nearby: 0 },
  code: { files: [{ path: 'backend/payment/X.java', line: 35, symbol: 'X.capture', excerpt: ['35: boom()'], tests: ['backend/payment/XTest.java'], module: 'payment' }] },
  git: { available: true, head: { commit: 'abc1234', date: '2026-10-01T00:00:00Z', subject: 'Head' }, commits_touching_suspected_files: [{ commit: 'def5678', date: '2026-10-14T00:00:00Z', subject: 'Change X' }], recent_commits_repository_wide: null, uncommitted_suspected_files: ['backend/payment/X.java'], note: 'Commits are CANDIDATES for investigation: a recent commit is not proof that it caused the incident.' },
  deployment: { available: false, reason: 'No deployment is recorded in the 24 hours before this signature first appeared.' }, configuration_changes: { available: false, reason: 'No source of environment or configuration change records is connected.' },
  related_incidents: [], hypotheses: [{ status: 'HYPOTHESIS', statement: 'A dependency may be failing.', evidence_ids: [11], confidence: 'MEDIUM', to_confirm: 'Check pool metrics.', basis: 'error category' }],
  likely_cause_status: 'UNCONFIRMED: ranked hypotheses only', remediation: ['Inspect backend/payment/X.java around line 35.'], reproduction: ['Send the same request on a local stack.'], validation_plan: ['cd backend && mvn -B -o -pl payment -am test'],
  additional_evidence_needed: ['Metrics for the same period.'], limitations: ['Commits, modified files and deployments are candidates.'], confidence: 'MEDIUM',
};
const LOGS: EngLogPage = {
  window: { since: '2026-10-14T10:00:00Z', until: '2026-10-15T10:00:00Z' }, count: 2, total: 30, offset: 0, limit: 25, has_more: true,
  records: [
    { id: 1, ts: '2026-10-15T09:00:00Z', service: 'backend', origin: 'real', level: 'ERROR', message: 'Payment failed', injection_flagged: false, correlation_id: 'cid-1', reference: 'SIG-00000001', category: 'DATABASE', exception_excerpt: 'at X.capture(X.java:35)', source: 'file:x' },
    { id: 2, ts: '2026-10-01T09:00:00Z', service: 'postgres', origin: 'synthetic', level: 'WARN', message: '[log message withheld: it contains instruction-like text]', injection_flagged: true },
  ],
};

const apiError = (status: number, message: string) =>
  new AxiosError(message, String(status), undefined, undefined, { status, data: { status, error: 'x', code: 'X', message, path: '/p', correlationId: null, timestamp: '', validationErrors: null }, statusText: '', headers: {}, config: {} as never });

const asAdmin = () => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'e@x.com', roles: ['ADMIN'] } });

beforeEach(() => {
  vi.resetAllMocks();
  asAdmin();
  vi.mocked(engineeringApi.sourceStatus).mockResolvedValue(STATUS);
  vi.mocked(engineeringApi.incidents).mockResolvedValue(page([inc(1), inc(2, { status: 'INCREASING', origin: 'synthetic' })]));
});
afterEach(() => { vi.useRealTimers(); });

describe('Engineering AI: incidents', () => {
  it('lists the ranked incidents with their reference, trend, origin, impact estimate and supporting records', async () => {
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    expect(within(card).getByTestId('eng-incident-status')).toHaveTextContent('new');
    expect(within(card).getByText('SIG-00000001')).toBeInTheDocument();
    expect(card).toHaveTextContent('endpoint /api/v1/payments/{id}/capture');
    expect(card).toHaveTextContent('Supporting records: L11, L12');
    expect(within(card).getByTestId('eng-incident-impact')).toHaveTextContent('an estimate from correlation ids, not a verified customer count');
    expect(within(card).getByTestId('eng-investigation-status')).toHaveTextContent('not investigated');
    expect(within(screen.getByTestId('eng-incident-SIG-00000002')).getByTestId('eng-incident-status')).toHaveTextContent('increasing');
    expect(screen.getByTestId('eng-incidents-summary')).toHaveTextContent('Showing 2 of 2 incident group(s)');
    expect(vi.mocked(engineeringApi.incidents).mock.calls[0][0]).toMatchObject({ hours: 24, limit: 10, offset: 0 });
  });

  it('shows ten at a time and loads the rest on request without dropping any', async () => {
    const user = userEvent.setup();
    const first = Array.from({ length: 10 }, (_, i) => inc(i + 1));
    vi.mocked(engineeringApi.incidents)
      .mockResolvedValueOnce(page(first, { total: 12, has_more: true }))
      .mockResolvedValueOnce(page([inc(11), inc(12)], { total: 12, offset: 10, has_more: false }));
    renderWithProviders(<EngineeringApp />);
    expect(await screen.findByTestId('eng-incidents-summary')).toHaveTextContent('Showing 10 of 12');
    await user.click(screen.getByTestId('eng-incidents-more'));
    await waitFor(() => expect(screen.getByTestId('eng-incidents-summary')).toHaveTextContent('Showing 12 of 12'));
    expect(vi.mocked(engineeringApi.incidents).mock.calls[1][0]).toMatchObject({ offset: 10 });
    expect(screen.queryByTestId('eng-incidents-more')).not.toBeInTheDocument();
    expect(screen.getByTestId('eng-incident-SIG-000000012')).toBeInTheDocument();
  });

  it('investigates one incident and shows facts, ranked hypotheses marked NOT confirmed, git as candidates and explicit "not available" for missing sources', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.investigateIncident).mockResolvedValue(REPORT);
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    await user.click(within(card).getByTestId('eng-investigate'));
    const report = await within(card).findByTestId('eng-incident-report');
    expect(vi.mocked(engineeringApi.investigateIncident).mock.calls[0][0]).toMatchObject({ signature: 'sig1'.padEnd(12, '0'), since: '2026-10-14T10:00:00Z', until: '2026-10-15T10:00:00Z', origin: 'real' });
    expect(within(report).getByTestId('eng-report-hypotheses')).toHaveTextContent('NOT confirmed');
    expect(report).toHaveTextContent('UNCONFIRMED: ranked hypotheses only');
    expect(report).toHaveTextContent('candidates, not proof');
    expect(report).toHaveTextContent('Uncommitted changes in: backend/payment/X.java');
    expect(report).toHaveTextContent('Not available: No deployment is recorded');
    expect(report).toHaveTextContent('Not available: No source of environment or configuration change records is connected.');
    expect(report).not.toHaveTextContent('no project stack frame');                           // files were mapped here, so that message must not appear
  });

  it('"Proposed resolution" shows the suggestions only and says nothing was applied', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.investigateIncident).mockResolvedValue(REPORT);
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    await user.click(within(card).getByTestId('eng-resolution-btn'));
    const res = await within(card).findByTestId('eng-resolution');
    expect(res).toHaveTextContent('Nothing has been changed');
    expect(res).toHaveTextContent('Send the same request on a local stack.');
    expect(within(card).queryByTestId('eng-incident-report')).not.toBeInTheDocument();
  });

  it('shows a clear error when the investigation fails and lets the user try again', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.investigateIncident).mockRejectedValueOnce(apiError(404, 'No log records with that signature in the selected window')).mockResolvedValue(REPORT);
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    await user.click(within(card).getByTestId('eng-investigate'));
    expect(await within(card).findByTestId('eng-incident-error')).toBeInTheDocument();
    await user.click(within(card).getByTestId('eng-investigate'));
    expect(await within(card).findByTestId('eng-incident-report')).toBeInTheDocument();
  });

  it('generates a coding prompt and copies it with a visible confirmation, without claiming anything was fixed', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    vi.mocked(engineeringApi.incidentPrompt).mockResolvedValue({ reference: 'SIG-00000001', prompt: '# Task: investigate and fix\nInspect the actual code first.', characters: 52, warnings: ['Generating this prompt does not fix the incident.'], origin: 'real' });
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    expect(within(card).getByTestId('eng-copy-prompt')).toBeDisabled();                      // nothing to copy yet
    await user.click(within(card).getByTestId('eng-gen-prompt'));
    const box = await within(card).findByTestId('eng-prompt');
    expect(box).toHaveTextContent('does not fix the incident');
    expect(within(box).getByRole('textbox', { name: 'coding prompt' })).toHaveValue('# Task: investigate and fix\nInspect the actual code first.');
    await user.click(within(card).getByTestId('eng-copy-prompt'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('# Task: investigate and fix\nInspect the actual code first.'));
    expect(await screen.findByTestId('eng-copy-note')).toHaveTextContent('Prompt copied to the clipboard.');
  });

  it('says so when the browser blocks the clipboard instead of pretending the prompt was copied', async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true });
    const exec = vi.fn().mockReturnValue(false);
    Object.defineProperty(document, 'execCommand', { value: exec, configurable: true });
    vi.mocked(engineeringApi.incidentPrompt).mockResolvedValue({ reference: 'SIG-00000001', prompt: 'p', characters: 1, warnings: [], origin: 'real' });
    renderWithProviders(<EngineeringApp />);
    const card = await screen.findByTestId('eng-incident-SIG-00000001');
    await user.click(within(card).getByTestId('eng-gen-prompt'));
    await within(card).findByTestId('eng-prompt');
    await user.click(within(card).getByTestId('eng-copy-prompt'));
    expect(await screen.findByTestId('eng-copy-note')).toHaveTextContent('Copy was blocked by the browser');
    expect(exec).toHaveBeenCalledWith('copy');
  });

  it('shows empty, loading and error states for the incident list', async () => {
    vi.mocked(engineeringApi.incidents).mockResolvedValueOnce(page([])).mockRejectedValue(apiError(500, 'The log store is down'));
    const { unmount } = renderWithProviders(<EngineeringApp />);
    expect(await screen.findByTestId('eng-incidents-empty')).toHaveTextContent('No WARN or ERROR groups in this window');
    unmount();
    renderWithProviders(<EngineeringApp />);
    expect(await screen.findByTestId('eng-incidents-error')).toHaveTextContent('The log store is down');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('Engineering AI: time ranges', () => {
  it('maps the presets to hours and a custom range to UTC timestamps, and refuses an invalid custom range', () => {
    expect(rangeQuery({ key: '15m', from: '', to: '' }).query).toEqual({ hours: 0.25 });
    expect(rangeQuery({ key: '1h', from: '', to: '' }).query).toEqual({ hours: 1 });
    expect(rangeQuery({ key: '7d', from: '', to: '' }).query).toEqual({ hours: 168 });
    const ok = rangeQuery({ key: 'custom', from: '2026-10-14T08:00', to: '2026-10-15T08:00' });
    expect(ok.error).toBeNull();
    expect(ok.query).toMatchObject({ since: expect.stringMatching(/Z$/), until: expect.stringMatching(/Z$/) });
    expect(rangeQuery({ key: 'custom', from: '', to: '' }).error).toMatch(/both a start and an end/);
    expect(rangeQuery({ key: 'custom', from: '2026-10-15T08:00', to: '2026-10-14T08:00' }).error).toMatch(/end must be after/);
    expect(rangeQuery({ key: 'custom', from: '2026-01-01T00:00', to: '2026-03-01T00:00' }).error).toMatch(/limited to 31 days/);
  });

  it('re-queries with the chosen preset and sends a custom range only when it is valid', async () => {
    const user = userEvent.setup();
    renderWithProviders(<EngineeringApp />);
    await screen.findByTestId('eng-incident-SIG-00000001');
    await user.click(screen.getByTestId('eng-range-15m'));
    await waitFor(() => expect(vi.mocked(engineeringApi.incidents).mock.calls.some((c) => c[0].hours === 0.25)).toBe(true));
    await user.click(screen.getByTestId('eng-range-custom'));
    expect(await screen.findByTestId('eng-range-error')).toHaveTextContent('both a start and an end');
    const calls = vi.mocked(engineeringApi.incidents).mock.calls.length;
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-14T08:00' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-14T20:00' } });
    await waitFor(() => expect(vi.mocked(engineeringApi.incidents).mock.calls.length).toBeGreaterThan(calls));
    const last = vi.mocked(engineeringApi.incidents).mock.calls.slice(-1)[0][0];
    expect(last.since).toMatch(/Z$/);
    expect(last.until).toMatch(/Z$/);
    expect(screen.getByText(/This window is in the past/)).toBeInTheDocument();                 // historical label
  });
});

describe('Engineering AI: live refresh and source health', () => {
  it('Refresh now re-reads the sources, reloads the screen and reports sources that could not be read', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.refresh).mockResolvedValue({ refreshed_at: '2026-10-15T10:00:00Z', throttled: false, stored_new: 7,
      sources: [{ service: 'backend', source: 'file:x', kind: 'file', status: 'ok', stored_new: 7 }, { service: 'postgres', source: 'docker:pg', kind: 'container', status: 'unavailable', reason: 'docker is not available here' }] });
    renderWithProviders(<EngineeringApp />);
    await screen.findByTestId('eng-incident-SIG-00000001');
    const before = vi.mocked(engineeringApi.incidents).mock.calls.length;
    await user.click(screen.getByTestId('eng-refresh'));
    expect(await screen.findByTestId('eng-unavailable')).toHaveTextContent('postgres (docker:pg) could not be read: docker is not available here');
    expect(screen.getByTestId('eng-last-refresh')).toHaveTextContent('7 new record(s) stored');
    await waitFor(() => expect(vi.mocked(engineeringApi.incidents).mock.calls.length).toBeGreaterThan(before));
  });

  it('shows STALE and DELAYED sources honestly', async () => {
    renderWithProviders(<EngineeringApp />);
    expect(await screen.findByTestId('eng-freshness')).toHaveTextContent('backend real: STALE (60 min behind)');
  });

  it('live mode polls on a timer and warns that the data may be out of date when a refresh fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(engineeringApi.refresh).mockRejectedValue({ response: { status: 503, data: { message: 'The log store is down' } } });
    renderWithProviders(<EngineeringApp />);
    await screen.findByTestId('eng-incident-SIG-00000001');
    fireEvent.click(screen.getByRole('checkbox', { name: 'live refresh' }));
    expect(engineeringApi.refresh).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(15_100); });
    await waitFor(() => expect(engineeringApi.refresh).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId('eng-stale')).toHaveTextContent('Live refresh is behind');
    await act(async () => { await vi.advanceTimersByTimeAsync(15_100); });
    await waitFor(() => expect(engineeringApi.refresh).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole('checkbox', { name: 'live refresh' }));                      // switching it off stops the polling
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(engineeringApi.refresh).toHaveBeenCalledTimes(2);
  });
});

describe('Engineering AI: logs', () => {
  it('shows historical records with filters, labels, pagination and a detail view', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.logs).mockResolvedValue(LOGS);
    renderWithProviders(<EngineeringApp />);
    await user.click(await screen.findByTestId('eng-tab-logs'));
    const table = await screen.findByTestId('eng-logs-table');
    expect(vi.mocked(engineeringApi.logs).mock.calls[0][0]).toMatchObject({ hours: 24, level: 'WARN', limit: 25, offset: 0 });
    expect(table).toHaveTextContent('Payment failed');
    expect(within(screen.getByTestId('eng-log-2')).getByText('historical')).toBeInTheDocument();     // older than a day
    expect(within(screen.getByTestId('eng-log-2')).getByText('synthetic')).toBeInTheDocument();
    expect(within(screen.getByTestId('eng-log-2')).getByText('instruction-like text withheld')).toBeInTheDocument();
    expect(screen.getByTestId('eng-logs-range')).toHaveTextContent('1–2 of 30');
    expect(screen.getByTestId('eng-logs-prev')).toBeDisabled();
    await user.click(screen.getByTestId('eng-logs-next'));
    await waitFor(() => expect(vi.mocked(engineeringApi.logs).mock.calls.some((c) => c[0].offset === 25)).toBe(true));
    await user.click(screen.getByTestId('eng-log-1'));
    const detail = await screen.findByTestId('eng-log-detail');
    expect(detail).toHaveTextContent('Correlation id: cid-1');
    expect(detail).toHaveTextContent('at X.capture(X.java:35)');
    expect(detail).toHaveTextContent('Secrets and personal data are redacted before storage');
  });

  it('passes the filters to the service and resets to the first page', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.logs).mockResolvedValue(LOGS);
    renderWithProviders(<EngineeringApp />);
    await user.click(await screen.findByTestId('eng-tab-logs'));
    await screen.findByTestId('eng-logs-table');
    await user.click(screen.getByTestId('eng-logs-next'));
    await user.type(screen.getByLabelText('Endpoint (e.g. /api/v1/orders)'), '/api/v1/orders');
    await waitFor(() => expect(vi.mocked(engineeringApi.logs).mock.calls.slice(-1)[0][0]).toMatchObject({ endpoint: '/api/v1/orders', offset: 0 }));
    await user.type(screen.getByLabelText('Correlation / request id'), 'abc');
    await waitFor(() => expect(vi.mocked(engineeringApi.logs).mock.calls.slice(-1)[0][0]).toMatchObject({ correlationId: 'abc' }));
  });

  it('shows empty and error states', async () => {
    const user = userEvent.setup();
    vi.mocked(engineeringApi.logs).mockResolvedValueOnce({ ...LOGS, count: 0, total: 0, records: [], has_more: false }).mockRejectedValue(apiError(500, 'The log store is down'));
    const { unmount } = renderWithProviders(<EngineeringApp />);
    await user.click(await screen.findByTestId('eng-tab-logs'));
    expect(await screen.findByTestId('eng-logs-empty')).toHaveTextContent('No log records match these filters');
    unmount();
    renderWithProviders(<EngineeringApp />);
    await userEvent.setup().click(await screen.findByTestId('eng-tab-logs'));
    expect(await screen.findByTestId('eng-logs-error')).toHaveTextContent('The log store is down');
  });
});
