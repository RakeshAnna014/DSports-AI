import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { SuggestionChips } from '@/features/ai/ResponseExtras';

describe('SuggestionChips', () => {
  it('a message chip sends its message and a link chip opens the page instead, without sending anything', async () => {
    const onPick = vi.fn();
    const onNavigate = vi.fn();
    render(
      <MemoryRouter>
        <SuggestionChips
          suggestions={[{ label: 'Find a cricket bat', message: 'Show me cricket bats' }, { label: 'Courts and venues', href: '/venues' }]}
          onPick={onPick}
          onNavigate={onNavigate}
        />
      </MemoryRouter>,
    );
    await userEvent.click(screen.getByText('Find a cricket bat'));
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ message: 'Show me cricket bats' }));
    await userEvent.click(screen.getByText('Courts and venues'));
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('ai-suggestion-link')).toHaveAttribute('href', '/venues');
  });
});
