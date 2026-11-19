import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { copyText, scorecardLink, ShareScorecardButton } from './ShareScorecardButton';

const setClipboard = (value: unknown) => Object.defineProperty(navigator, 'clipboard', { configurable: true, value });
const setExec = (impl: (command: string) => boolean) => { document.execCommand = impl as typeof document.execCommand; };

afterEach(() => {
  setClipboard(undefined);
  vi.restoreAllMocks();
});

describe('sharing the scorecard link', () => {
  it('builds the public link from this site and the match id', () => {
    expect(scorecardLink('m-1')).toBe(`${window.location.origin}/m/m-1`);
  });

  it('copies with the clipboard when the page is secure', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    render(<ShareScorecardButton matchId="m-1" title="Lions vs Tigers" />);
    setClipboard({ writeText });
    await user.click(screen.getByTestId('copy-link'));

    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/m/m-1`);
    expect(await screen.findByTestId('copied')).toBeInTheDocument();
  });

  it('copies over plain http, where there is no clipboard API, with the older copy command', async () => {
    const user = userEvent.setup();
    setClipboard(undefined);
    const exec = vi.fn().mockReturnValue(true);
    setExec(exec);
    render(<ShareScorecardButton matchId="m-1" title="Lions vs Tigers" />);
    await user.click(screen.getByTestId('copy-link'));

    expect(exec).toHaveBeenCalledWith('copy');
    expect(await screen.findByTestId('copied')).toBeInTheDocument();
    expect(document.querySelector('textarea')).toBeNull();
  });

  it('shows the link to copy by hand when no way of copying works', async () => {
    const user = userEvent.setup();
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) });
    setExec(() => false);
    render(<ShareScorecardButton matchId="m-1" title="Lions vs Tigers" />);
    await user.click(screen.getByTestId('copy-link'));

    expect(await screen.findByLabelText('Copy this link')).toHaveValue(`${window.location.origin}/m/m-1`);
    expect(screen.queryByTestId('copied')).toBeNull();
  });

  it('sends the match title and link to WhatsApp as an ordinary link that opens a new tab safely', () => {
    render(<ShareScorecardButton matchId="m-1" title="Lions vs Tigers" />);
    const link = screen.getByTestId('whatsapp-share');

    expect(link.getAttribute('href')).toBe(`https://wa.me/?text=${encodeURIComponent(`Lions vs Tigers: follow the live scorecard ${window.location.origin}/m/m-1`)}`);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
  });

  it('copyText reports failure instead of throwing', async () => {
    setClipboard(undefined);
    setExec(() => { throw new Error('blocked'); });

    await expect(copyText('x')).resolves.toBe(false);
  });
});
