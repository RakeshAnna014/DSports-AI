import { useState } from 'react';
import { Alert, Button, Stack, TextField } from '@mui/material';
import { ContentCopy, WhatsApp } from '@mui/icons-material';

/** The link anyone can open without signing in. */
export const scorecardLink = (matchId: string): string => `${window.location.origin}/m/${matchId}`;

/**
 * Copies text to the clipboard. The modern clipboard API exists only on secure pages (https or localhost), and a phone on the local network opens the app over
 * plain http, so it falls back to the old copy command; if neither works the caller shows the link to copy by hand.
 */
export const copyText = async (text: string): Promise<boolean> => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the older way
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand('copy');
    document.body.removeChild(area);
    return copied;
  } catch {
    return false;
  }
};

/** Copy the public scorecard link, or send it on WhatsApp. The link is the same for everyone and shows no private data. */
export const ShareScorecardButton = ({ matchId, title, size = 'small' }: { matchId: string; title: string; size?: 'small' | 'medium' }) => {
  const [state, setState] = useState<'idle' | 'copied' | 'manual'>('idle');
  const link = scorecardLink(matchId);
  const text = `${title}: follow the live scorecard ${link}`;

  const copy = async () => {
    setState((await copyText(link)) ? 'copied' : 'manual');
  };

  return (
    <Stack spacing={1} data-testid="share-scorecard">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        <Button size={size} variant="outlined" startIcon={<ContentCopy />} onClick={() => void copy()} data-testid="copy-link">Copy link</Button>
        <Button size={size} variant="outlined" color="success" startIcon={<WhatsApp />} component="a" href={`https://wa.me/?text=${encodeURIComponent(text)}`} target="_blank"
          rel="noopener noreferrer" data-testid="whatsapp-share">Share on WhatsApp</Button>
      </Stack>
      {state === 'copied' && <Alert severity="success" sx={{ py: 0 }} data-testid="copied">Link copied.</Alert>}
      {state === 'manual' && (
        <TextField size="small" label="Copy this link" value={link} slotProps={{ input: { readOnly: true } }} onFocus={(e) => e.target.select()} data-testid="manual-link" />
      )}
    </Stack>
  );
};
