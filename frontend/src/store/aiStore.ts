import { create } from 'zustand';
import { aiApi, getAiErrorMessage, isAiUnreachable, type CaseConversation } from '@/api/ai';
import type { AiAction, AiProductRef, AiResponse, ChatEntry } from '@/types/ai';
import { useCartStore } from '@/store/cartStore';

const CONVERSATION_KEY = 'dsports_ai_conversation';

const loadConversation = (): string | null => {
  try {
    return sessionStorage.getItem(CONVERSATION_KEY);
  } catch {
    return null;
  }
};

const saveConversation = (id: string | null) => {
  try {
    if (id) sessionStorage.setItem(CONVERSATION_KEY, id);
    else sessionStorage.removeItem(CONVERSATION_KEY);
  } catch {
    // the conversation id is a convenience; the assistant works without it
  }
};

const entryId = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/** The customer's own support case this conversation is about (the service re-validates it with the customer's token). */
export interface CaseContext {
  caseNumber: string;
  subject?: string;
}

/** Rebuilds a resumed conversation's plain-text transcript as ordinary chat entries (text only: no ids, tokens or tool data are ever stored in it). */
export const entriesFromTranscript = (conversationId: string, transcript: CaseConversation['transcript']): ChatEntry[] =>
  transcript.map((m, i): ChatEntry => m.role === 'user'
    ? { id: `resumed-${i}`, role: 'user', text: m.text }
    : { id: `resumed-${i}`, role: 'assistant', response: { conversationId, status: 'ok', intent: 'RESUMED', tier: 0, message: m.text, messageSource: 'deterministic', products: [], comparison: null, orders: [], tracking: null, cart: null, payments: [], refunds: [], eligibility: null, actionResult: null, actions: [], sources: [], requiresConfirmation: false, confirmationToken: null, type: 'TEXT', suggestions: [], disclaimers: [], errorCode: null, meta: { latencyMs: 0, llmCalls: 0 } } });

interface AiState {
  open: boolean;
  entries: ChatEntry[];
  caseContext: CaseContext | null;
  /** Opens the assistant about one of the customer's cases, resuming the earlier conversation about it when there is one. */
  openForCase: (ctx: CaseContext) => Promise<void>;
  leaveCase: () => void;
  conversationId: string | null;
  sending: boolean;
  pendingAction: AiAction | null;
  confirming: boolean;
  /** The product the conversation is about (set by "Ask AI", confirmed by the service) and the products being compared. */
  productContext: AiProductRef | null;
  comparisonProducts: AiProductRef[];
  pendingSelectId: string | null;
  pendingClear: boolean;
  focusToken: number;
  askAboutProduct: (product: AiProductRef, options?: { focus?: boolean }) => void;
  clearProductContext: () => void;
  setOpen: (open: boolean) => void;
  send: (text: string, choiceId?: string | null) => Promise<void>;
  /** Re-sends the last request after a failure. */
  retry: () => Promise<void>;
  lastRequest: { text: string; choiceId?: string | null } | null;
  confirmPending: () => Promise<void>;
  dismissPending: () => void;
  reset: () => void;
}

const fail = (err: unknown): ChatEntry => ({
  id: entryId(),
  role: 'error',
  unavailable: isAiUnreachable(err),
  text: isAiUnreachable(err)
    ? 'The AI assistant is not available right now. The rest of the shop works as usual: you can still browse, check out and track orders.'
    : getAiErrorMessage(err),
});

export const useAiStore = create<AiState>((set, get) => ({
  open: false,
  entries: [],
  conversationId: loadConversation(),
  caseContext: null,
  sending: false,
  pendingAction: null,
  confirming: false,
  productContext: null,
  comparisonProducts: [],
  pendingSelectId: null,
  pendingClear: false,
  focusToken: 0,
  lastRequest: null,

  askAboutProduct: (product, options) =>
    set((s) => ({
      productContext: product,
      comparisonProducts: [],
      pendingSelectId: product.productId,
      pendingClear: false,
      focusToken: options?.focus === false ? s.focusToken : s.focusToken + 1,
    })),

  clearProductContext: () => set({ productContext: null, comparisonProducts: [], pendingSelectId: null, pendingClear: true }),

  setOpen: (open) => set({ open }),

  openForCase: async (ctx) => {
    if (get().caseContext?.caseNumber === ctx.caseNumber && get().entries.length > 0) {
      set({ open: true });
      return;
    }
    saveConversation(null);
    set({ open: true, caseContext: ctx, entries: [], conversationId: null, pendingAction: null, sending: true, productContext: null, comparisonProducts: [], pendingSelectId: null, pendingClear: false });
    try {
      const resumed = await aiApi.caseConversation(ctx.caseNumber);
      if (get().caseContext?.caseNumber !== ctx.caseNumber) return;
      if (resumed.conversationId) {
        saveConversation(resumed.conversationId);
        set({ conversationId: resumed.conversationId, entries: entriesFromTranscript(resumed.conversationId, resumed.transcript) });
      }
    } catch (err) {
      // Resuming is a convenience: the case chat still works as a new conversation.
      set((s) => ({ entries: [...s.entries, fail(err)] }));
    } finally {
      set({ sending: false });
    }
  },

  leaveCase: () => {
    saveConversation(null);
    set({ caseContext: null, entries: [], conversationId: null, pendingAction: null, lastRequest: null });
  },

  send: async (text, choiceId) => {
    const message = text.trim();
    if (!message || get().sending) return;
    set((s) => ({ entries: [...s.entries, { id: entryId(), role: 'user', text: message }], sending: true, lastRequest: { text: message, choiceId } }));
    try {
      const { pendingSelectId, pendingClear } = get();
      const response = await aiApi.chat(message, get().conversationId, { selectedProductId: pendingSelectId, clearProductContext: pendingClear, choiceId, caseRef: get().caseContext?.caseNumber ?? null });
      saveConversation(response.conversationId);
      set((s) => ({
        // The service is the source of truth for what the conversation is about (it re-validated the product against DSports).
        productContext: response.context?.selectedProduct ?? null,
        comparisonProducts: response.context?.comparisonProducts ?? [],
        pendingSelectId: null,
        pendingClear: false,
        entries: [...s.entries, { id: entryId(), role: 'assistant', response }],
        conversationId: response.conversationId,
        pendingAction: response.requiresConfirmation ? (response.actions[0] ?? null) : null,
      }));
    } catch (err) {
      set((s) => ({ entries: [...s.entries, fail(err)] }));
    } finally {
      set({ sending: false });
    }
  },

  retry: async () => {
    const last = get().lastRequest;
    if (!last) return;
    // drop the failed exchange from the transcript, then ask again
    set((s) => ({ entries: s.entries.slice(0, Math.max(0, s.entries.length - 2)) }));
    await get().send(last.text, last.choiceId);
  },

  confirmPending: async () => {
    const action = get().pendingAction;
    if (!action || get().confirming) return;
    set({ confirming: true });
    try {
      const response: AiResponse = await aiApi.confirm(action.confirmationToken, get().conversationId);
      set((s) => ({ entries: [...s.entries, { id: entryId(), role: 'assistant', response }], pendingAction: null }));
      if (response.status === 'ok' && response.actionResult) {
        // The assistant added something to the cart through the shop API: refresh the header badge.
        void useCartStore.getState().refreshCart();
      }
    } catch (err) {
      set((s) => ({ entries: [...s.entries, fail(err)], pendingAction: null }));
    } finally {
      set({ confirming: false });
    }
  },

  dismissPending: () => {
    set({ pendingAction: null });
    void get().send('no');
  },

  reset: () => {
    saveConversation(null);
    set({ entries: [], conversationId: null, caseContext: null, pendingAction: null, sending: false, confirming: false, productContext: null, comparisonProducts: [],
          pendingSelectId: null, pendingClear: false });
  },
}));
