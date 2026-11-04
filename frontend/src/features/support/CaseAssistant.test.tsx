import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import MyCasesPage from './MyCasesPage';
import AssistantPanel from '@/features/ai/AssistantPanel';
import { supportApi } from '@/api/support';
import { aiApi } from '@/api/ai';
import { useAiStore } from '@/store/aiStore';
import { useAuthStore } from '@/store/authStore';
import type { ApiCase, ApiSummary } from '@/types/support';
import type { AiResponse } from '@/types/ai';

vi.mock('@/api/support', async (orig) => ({ ...(await orig<typeof import('@/api/support')>()), supportApi: { mine: vi.fn(), get: vi.fn(), create: vi.fn(), message: vi.fn(), escalate: vi.fn(), reopen: vi.fn() } }));
vi.mock('@/api/ai', async (orig) => ({ ...(await orig<typeof import('@/api/ai')>()), aiApi: { chat: vi.fn(), confirm: vi.fn(), caseConversation: vi.fn() } }));

const REF = 'CASE-20261012-000001';
const SUMMARY: ApiSummary = { id: 'c1', caseNumber: REF, category: 'DELAYED_DELIVERY', status: 'OPEN', priority: 'MEDIUM', subject: 'Order has not arrived', orderNumber: 'ORD-1',
  escalation: { state: 'NONE', reason: null, humanAccepted: false }, createdAt: '2026-10-12T10:00:00Z', updatedAt: '2026-10-12T10:00:00Z' };
const CASE: ApiCase = { ...SUMMARY, description: 'My order has not arrived.', evidence: [], policyCitations: [], missingInformation: [], assignedToSupportTeam: false, canReopen: false,
  reopenWindowDays: 7, version: 1, messages: [], timeline: [] };

const reply = (message: string, over: Partial<AiResponse> = {}): AiResponse => ({
  conversationId: 'conv-1', status: 'ok', intent: 'CASE_STATUS', tier: 0, message, messageSource: 'deterministic', products: [], comparison: null, orders: [], tracking: null, cart: null,
  payments: [], refunds: [], eligibility: null, actionResult: null, actions: [], sources: [], requiresConfirmation: false, confirmationToken: null, type: 'TEXT', suggestions: [],
  disclaimers: [], errorCode: null, meta: { latencyMs: 1, llmCalls: 0 }, ...over,
});

const page = () => renderWithProviders(
  <Routes><Route path="/support/:ref?" element={<><MyCasesPage /><AssistantPanel /></>} /></Routes>, `/support/${REF}`);

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'a@b.c', roles: ['CUSTOMER'] } });
  useAiStore.getState().reset();
  useAiStore.getState().setOpen(false);
  vi.mocked(supportApi.mine).mockResolvedValue([SUMMARY]);
  vi.mocked(supportApi.get).mockResolvedValue(CASE);
  vi.mocked(aiApi.caseConversation).mockResolvedValue({ caseRef: REF, conversationId: null, transcript: [] });
});

describe('Case-linked assistant', () => {
  it('opens the assistant about this case, shows the case in the header and sends its number with every message', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.chat).mockResolvedValue(reply(`Case ${REF} is OPEN.`));
    page();
    await user.click(await screen.findByTestId('case-ai-help'));
    expect(await screen.findByTestId('ai-case-context')).toHaveTextContent(`About case ${REF} · Order has not arrived`);
    expect(aiApi.caseConversation).toHaveBeenCalledWith(REF);
    expect(screen.getByText(/only a person can resolve or close a case/i)).toBeInTheDocument();
    await user.type(screen.getByTestId('ai-input'), 'What is the latest on this case?');
    await user.click(screen.getByTestId('ai-send'));
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalled());
    expect(vi.mocked(aiApi.chat).mock.calls[0][2]).toMatchObject({ caseRef: REF });
    expect(await screen.findByText(`Case ${REF} is OPEN.`)).toBeInTheDocument();
  });

  it('resumes the earlier conversation about the case with its transcript and keeps using the same conversation', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.caseConversation).mockResolvedValue({ caseRef: REF, conversationId: 'conv-9', transcript: [
      { role: 'user', text: 'Where is my parcel?', at: '2026-10-12T10:00:00Z' }, { role: 'assistant', text: 'It is out for delivery.', at: '2026-10-12T10:00:01Z' }] });
    vi.mocked(aiApi.chat).mockResolvedValue(reply('Still on its way.', { conversationId: 'conv-9' }));
    page();
    await user.click(await screen.findByTestId('case-ai-help'));
    expect(await screen.findByText('Where is my parcel?')).toBeInTheDocument();
    expect(screen.getByText('It is out for delivery.')).toBeInTheDocument();
    await user.type(screen.getByTestId('ai-input'), 'Any news?');
    await user.click(screen.getByTestId('ai-send'));
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalledWith('Any news?', 'conv-9', expect.objectContaining({ caseRef: REF })));
  });

  it('still works as a new conversation, with a visible note, when the earlier one cannot be loaded', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.caseConversation).mockRejectedValue(new Error('down'));
    page();
    await user.click(await screen.findByTestId('case-ai-help'));
    expect(await screen.findByTestId('ai-error')).toBeInTheDocument();
    expect(screen.getByTestId('ai-input')).toBeEnabled();
  });

  it('leaving the case returns to a normal assistant conversation that no longer sends the case', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.chat).mockResolvedValue(reply('Hello.'));
    page();
    await user.click(await screen.findByTestId('case-ai-help'));
    await screen.findByTestId('ai-case-context');
    await user.click(screen.getByRole('button', { name: 'Leave case' }));
    await waitFor(() => expect(screen.queryByTestId('ai-case-context')).not.toBeInTheDocument());
    await user.type(screen.getByTestId('ai-input'), 'Find a cricket bat');
    await user.click(screen.getByTestId('ai-send'));
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalled());
    expect(vi.mocked(aiApi.chat).mock.calls[0][2]).toMatchObject({ caseRef: null });
  });

  it('refreshes the case from the backend after the assistant has talked, so changes made through confirmed actions show up', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.chat).mockResolvedValue(reply('Done. I added your message.'));
    page();
    await user.click(await screen.findByTestId('case-ai-help'));
    const before = vi.mocked(supportApi.get).mock.calls.length;
    await user.type(screen.getByTestId('ai-input'), 'Add a message to my case: hello');
    await user.click(screen.getByTestId('ai-send'));
    await waitFor(() => expect(vi.mocked(supportApi.get).mock.calls.length).toBeGreaterThan(before));
  });

  it('a resolved case still offers the assistant, worded as a question about the case', async () => {
    vi.mocked(supportApi.get).mockResolvedValue({ ...CASE, status: 'RESOLVED', canReopen: true });
    page();
    expect(await screen.findByTestId('case-ai-help')).toHaveTextContent('Ask the assistant about this case');
  });
});
