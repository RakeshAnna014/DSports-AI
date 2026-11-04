import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SupportFeedback } from './SupportFeedback';
import { supportStaffAi } from '@/api/supportStaffAi';

vi.mock('@/api/supportStaffAi', () => ({ supportStaffAi: { feedback: vi.fn() } }));

describe('Support feedback', () => {
  it('sends the customer\'s answer for this conversation once and thanks them', async () => {
    vi.mocked(supportStaffAi.feedback).mockResolvedValue(undefined);
    render(<SupportFeedback conversationId="conv-12345678" />);
    await userEvent.click(screen.getByTestId('feedback-NOT_HELPFUL'));
    await waitFor(() => expect(screen.getByTestId('support-feedback-thanks')).toBeInTheDocument());
    expect(supportStaffAi.feedback).toHaveBeenCalledTimes(1);
    expect(supportStaffAi.feedback).toHaveBeenCalledWith('conv-12345678', 'NOT_HELPFUL');
  });

  it('does not claim the answer was recorded when saving fails', async () => {
    vi.mocked(supportStaffAi.feedback).mockRejectedValue(new Error('down'));
    render(<SupportFeedback conversationId="conv-12345678" />);
    await userEvent.click(screen.getByTestId('feedback-HELPFUL'));
    expect(await screen.findByText(/could not be saved/i)).toBeInTheDocument();
    expect(screen.queryByTestId('support-feedback-thanks')).not.toBeInTheDocument();
  });
});
