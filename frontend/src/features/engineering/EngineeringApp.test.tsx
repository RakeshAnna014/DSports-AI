import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import EngineeringApp from './EngineeringApp';
import { engineeringApi, type EngInvestigation, type EngIncidentPage, type EngSourceStatus } from '@/api/engineeringAi';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/engineeringAi', () => ({
  engineeringApi: { investigate: vi.fn(), sources: vi.fn(), sourceStatus: vi.fn(), refresh: vi.fn(), logs: vi.fn(), incidents: vi.fn(), investigateIncident: vi.fn(), incidentPrompt: vi.fn() },
}));

const RESULT: EngInvestigation = {
  question: 'Why did checkout fail?', interpretation: ['Time window: last 24 hours'], intent: 'INVESTIGATE', window: { since: '2026-10-13T00:00:00Z', until: '2026-10-13T15:00:00Z' },
  facts: ['506 x [ERROR] TimeoutException: Pool acquire timed out [L12][L11]'],
  hypotheses: [{ status: 'HYPOTHESIS', statement: 'A recurring error points at a database problem; this is a hypothesis from log text only.', evidence_ids: [12], confidence: 'MEDIUM', to_confirm: 'database-side logs', category: 'DATABASE' }],
  clusters: [{ signature: 'abc', text: 'TimeoutException: Pool acquire timed out after <n> ms', count: 506, first_seen: '', last_seen: '', services: ['backend'], level: 'ERROR', category: 'DATABASE', sample_ids: [12], previous_window_count: 0, change: 'NEW_IN_WINDOW' }],
  evidence: [
    { id: 12, ts: '2026-10-13T13:05:00Z', service: 'backend', origin: 'synthetic', level: 'ERROR', message: 'Payment capture failed status=500', correlation_id: 'abcdef12-0000', injection_flagged: false },
    { id: 13, ts: '2026-10-13T13:06:00Z', service: 'backend', origin: 'synthetic', level: 'ERROR', message: '[log message withheld: it contains instruction-like text]', injection_flagged: true },
  ],
  next_steps: ['Check database connection-pool metrics'], limitations: ['Some of the stored records are SYNTHETIC'],
  sources_connected: [], summary: { text: 'Window ... Hypotheses (NOT confirmed): (1) ...', source: 'deterministic', guard: null }, meta: { latency_ms: 40, records_in_evidence: 2, llm_used: false },
};

const STATUS: EngSourceStatus = {
  server_time: '2026-10-15T10:00:00Z', live_threshold_seconds: 120, delayed_threshold_seconds: 900,
  sources: [
    { service: 'backend', origin: 'real', source: 'file:x', records: 794, first_ts: '2026-10-15T08:00:00Z', last_ts: '2026-10-15T09:59:50Z', lag_seconds: 10, freshness: 'LIVE' },
    { service: 'backend', origin: 'synthetic', source: 's', records: 100, first_ts: '2026-10-13T08:00:00Z', last_ts: '2026-10-13T09:59:50Z', lag_seconds: 99999, freshness: 'SYNTHETIC' },
  ],
};
const NO_INCIDENTS: EngIncidentPage = { window: { since: '2026-10-14T10:00:00Z', until: '2026-10-15T10:00:00Z' }, total: 0, offset: 0, limit: 10, has_more: false, incidents: [], ranking: 'r', note: 'n', data_freshness: [] };

const asRole = (roles: string[]) => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'e@x.com', roles } });
const openAsk = async (user: ReturnType<typeof userEvent.setup>) => user.click(await screen.findByTestId('eng-tab-ask'));

describe('Engineering AI page', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(engineeringApi.sourceStatus).mockResolvedValue(STATUS);
    vi.mocked(engineeringApi.incidents).mockResolvedValue(NO_INCIDENTS);
  });

  it('has a Home action in the header that goes to the storefront home page and leaves the investigation controls in place', async () => {
    const user = userEvent.setup();
    asRole(['ADMIN']);
    renderWithProviders(<Routes><Route path="/engineering-ai" element={<EngineeringApp />} /><Route path="/" element={<div data-testid="home-page">Home page</div>} /></Routes>, '/engineering-ai');
    expect(screen.getByTestId('eng-tab-incidents')).toBeInTheDocument();
    await openAsk(user);
    expect(screen.getByTestId('eng-question')).toBeInTheDocument();
    expect(screen.getByTestId('eng-ask')).toBeDisabled();
    const home = screen.getByRole('link', { name: 'Home' });
    expect(home).toHaveAttribute('href', '/');
    await user.click(home);
    expect(await screen.findByTestId('home-page')).toBeInTheDocument();
    expect(screen.queryByTestId('eng-app')).not.toBeInTheDocument();
  });

  it('is restricted for customers and support staff and never calls the API', () => {
    for (const roles of [['CUSTOMER'], ['SUPPORT_EXECUTIVE']]) {
      asRole(roles);
      const { unmount } = renderWithProviders(<EngineeringApp />);
      expect(screen.getByTestId('eng-restricted')).toBeInTheDocument();
      unmount();
    }
    expect(engineeringApi.sourceStatus).not.toHaveBeenCalled();
    expect(engineeringApi.incidents).not.toHaveBeenCalled();
    expect(engineeringApi.investigate).not.toHaveBeenCalled();
  });

  it('lists the connected sources with real and synthetic clearly distinguished and shows how fresh each one is', async () => {
    asRole(['ADMIN']);
    renderWithProviders(<EngineeringApp />);
    const box = await screen.findByTestId('eng-sources');
    await waitFor(() => expect(box).toHaveTextContent('backend · real · 794 records'));
    expect(box).toHaveTextContent('backend · synthetic · 100 records');
    const fresh = await screen.findByTestId('eng-freshness');
    expect(fresh).toHaveTextContent('backend real: LIVE (10s behind)');
    expect(fresh).toHaveTextContent('backend synthetic: SYNTHETIC');
  });

  it('investigates a question and shows facts, hypotheses labelled NOT confirmed, clusters and evidence with origin labels', async () => {
    const user = userEvent.setup();
    asRole(['ADMIN']);
    vi.mocked(engineeringApi.investigate).mockResolvedValue(RESULT);
    renderWithProviders(<EngineeringApp />);
    await openAsk(user);
    expect(screen.getByTestId('eng-ask')).toBeDisabled();
    await user.type(screen.getByTestId('eng-question'), 'Why did checkout fail?');
    await user.click(screen.getByTestId('eng-ask'));
    const result = await screen.findByTestId('eng-result');
    expect(engineeringApi.investigate).toHaveBeenCalledWith('Why did checkout fail?', undefined, false);
    expect(within(screen.getByTestId('eng-hypotheses')).getByText('NOT confirmed')).toBeInTheDocument();
    expect(within(screen.getByTestId('eng-clusters')).getByText('506')).toBeInTheDocument();
    const evidence = screen.getByTestId('eng-evidence');
    expect(within(evidence).getAllByText('synthetic')).toHaveLength(2);
    expect(within(evidence).getByText('instruction-like text withheld')).toBeInTheDocument();
    expect(screen.getByTestId('eng-summary-source')).toHaveTextContent('Deterministic (no AI)');
    expect(result).toHaveTextContent('Some of the stored records are SYNTHETIC');
  });

  it('labels an AI-written summary and passes the origin and the AI switch to the service', async () => {
    const user = userEvent.setup();
    asRole(['ADMIN']);
    vi.mocked(engineeringApi.investigate).mockResolvedValue({ ...RESULT, summary: { text: 'May indicate pool exhaustion [L12].', source: 'llm', guard: { ok: true } } });
    renderWithProviders(<EngineeringApp />);
    await openAsk(user);
    await user.click(screen.getByText('Why did checkout fail?'));
    await user.click(screen.getByRole('checkbox', { name: /AI-written summary/ }));
    await user.click(screen.getByTestId('eng-ask'));
    expect(await screen.findByTestId('eng-summary-source')).toHaveTextContent('AI-written, checked against the evidence');
    expect(engineeringApi.investigate).toHaveBeenCalledWith('Why did checkout fail?', undefined, true);
  });

  it('shows a calm error when the service is unavailable or refuses the request', async () => {
    const user = userEvent.setup();
    asRole(['ADMIN']);
    vi.mocked(engineeringApi.investigate).mockRejectedValue({ response: { status: 403 } });
    renderWithProviders(<EngineeringApp />);
    await openAsk(user);
    await user.type(screen.getByTestId('eng-question'), 'why did it fail');
    await user.click(screen.getByTestId('eng-ask'));
    expect(await screen.findByTestId('eng-error')).toHaveTextContent('restricted to authorised engineering users');
  });
});
