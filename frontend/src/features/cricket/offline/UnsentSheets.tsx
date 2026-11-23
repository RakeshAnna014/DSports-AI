import { useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import { describe as describeCommand, type NameBook } from './localView';
import { exportItems } from './exportText';
import { chainOf, previewRebase } from './queue';
import type { QueueItem, QueueState, SetAsideReason } from './types';

/** The sheets of the offline screen: what the scorer decides when somebody else scored, what is saved but not in the match, and how to keep a copy. Plain words, no codes. */

export const reasonText: Record<SetAsideReason, string> = {
  CONFLICT_KEPT_THEIRS: "you chose the other scorer's version",
  CONFLICT_NOT_APPLICABLE: "it could not be added after the other scorer's balls",
  LOCKED: 'the correction window closed before it reached the server',
  FORBIDDEN: 'you can no longer score this match, or it no longer exists',
  REJECTED_BY_SERVER: 'the server refused it',
  BLOCKED_BY_EARLIER: 'an earlier ball was set aside',
  OTHER_ACCOUNT: 'it was saved by another account on this phone',
  CORRUPTED: 'it could not be read',
  SCORER_CHOICE: 'you set it aside',
};

const score = (log: ReturnType<typeof previewRebase>['log']): string => {
  const innings = log.score.innings[log.score.innings.length - 1];
  return innings === undefined ? 'nothing scored yet' : `${innings.runs}/${innings.wickets}`;
};

/** Somebody else scored while this phone was offline. The scorer sees both sets of balls and chooses; nothing is merged without them. */
export const ConflictSheet = ({ state, names, onRebase, onKeepTheirs, onNotNow }: { state: QueueState; names: NameBook; onRebase: () => void; onKeepTheirs: () => void; onNotNow: () => void }) => {
  const view = state.blocked?.kind === 'CONFLICT' ? state.blocked.view : null;
  if (view === null) {
    return null;
  }
  const chain = chainOf(state.items);
  const lastSeen = chain.length > 0 ? chain[0].expectedSeq : state.confirmedSeq;
  // Theirs is whatever is not one of this phone's own waiting balls: another account, or the same account on another device (the server calls that "mine" too).
  const own = new Set(chain.map((i) => i.clientEventId));
  const foreign = view.entries.filter((e) => e.seq > lastSeen && !(e.clientEventId !== null && own.has(e.clientEventId)));
  const preview = previewRebase(state);
  const who = names.player;
  const text = (e: (typeof foreign)[number]): string => (e.kind === 'COMMAND' ? describeCommand(e.command, who) : e.kind === 'UNDO' ? 'an undo' : 'the match was reopened by an administrator');
  return (
    <Dialog open fullWidth maxWidth="xs" data-testid="conflict-sheet">
      <DialogTitle>Someone else scored while you were away</DialogTitle>
      <DialogContent>
        <Typography variant="subtitle2" data-testid="conflict-theirs-count">{foreign.length === 1 ? '1 change by someone else' : `${foreign.length} changes by someone else`}</Typography>
        <Box sx={{ maxHeight: 140, overflow: 'auto', mb: 1 }}>
          {foreign.map((e) => <Typography key={e.seq} variant="body2" data-testid="conflict-event">{e.seq}. {text(e)}</Typography>)}
        </Box>
        <Typography variant="subtitle2" data-testid="conflict-mine-count">{chain.length === 1 ? 'Your 1 unsent ball' : `Your ${chain.length} unsent balls`}</Typography>
        <Box sx={{ maxHeight: 140, overflow: 'auto', mb: 1 }}>
          {chain.map((i) => <Typography key={i.n} variant="body2" data-testid="conflict-mine">{i.label}</Typography>)}
        </Box>
        {preview.applicable === preview.total ? (
          <Alert severity="info" data-testid="conflict-preview">All of yours can be added after theirs: the score goes to {score(preview.log)}. The server still checks every ball.</Alert>
        ) : (
          <Alert severity="warning" data-testid="conflict-preview">
            {preview.applicable === 0 ? 'None of yours' : `The first ${preview.applicable} of yours`} can be added after theirs; the next one cannot ({preview.refusal?.message}). The rest would be saved but set aside, not lost.
          </Alert>
        )}
      </DialogContent>
      <DialogActions sx={{ flexWrap: 'wrap', gap: 1 }}>
        <Button onClick={onNotNow} data-testid="conflict-not-now">Not now</Button>
        <Button onClick={onKeepTheirs} data-testid="conflict-keep-theirs">Keep theirs, set mine aside</Button>
        <Button variant="contained" onClick={onRebase} data-testid="conflict-rebase">Add mine after theirs</Button>
      </DialogActions>
    </Dialog>
  );
};

/** The server refused a ball the phone accepted: the two copies of the rules disagree. Everything is kept; the scorer chooses. */
export const RejectedSheet = ({ state, onSetAside, onNotNow }: { state: QueueState; onSetAside: (n: number) => void; onNotNow: () => void }) => {
  const blocked = state.blocked?.kind === 'REJECTED' ? state.blocked : null;
  if (blocked === null) {
    return null;
  }
  const item = state.items.find((i) => i.n === blocked.n);
  return (
    <Dialog open fullWidth maxWidth="xs" data-testid="rejected-sheet">
      <DialogTitle>The server did not accept a ball</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1 }}>{item ? `“${item.label}”` : 'A ball'}: {blocked.message}</Typography>
        <Alert severity="info">Offline scoring is switched off on this phone until the page is reloaded. Every ball is still saved here. Choose what to do with this ball and the ones after it, then score online one ball at a time.</Alert>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Report code: {blocked.violation}</Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onNotNow} data-testid="rejected-not-now">Keep waiting</Button>
        <Button variant="contained" onClick={() => onSetAside(blocked.n)} data-testid="rejected-set-aside">Set aside this and the rest</Button>
      </DialogActions>
    </Dialog>
  );
};

/** Balls saved on this phone that are not in the match, with the reason, and the ways to deal with each. Nothing here expires and nothing is deleted without a named confirmation. */
export const SetAsideSheet = ({ items, locked, canSendAgain, onSendAgain, onDiscard, onExport, onClose }: {
  items: QueueItem[];
  locked: boolean;
  canSendAgain: boolean;
  onSendAgain: () => void;
  onDiscard: (n: number) => void;
  onExport: () => void;
  onClose: () => void;
}) => {
  const [asking, setAsking] = useState<QueueItem | null>(null);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid="set-aside-sheet">
      <DialogTitle>Saved on this phone, not in the match</DialogTitle>
      <DialogContent>
        <Alert severity="info" sx={{ mb: 1 }}>
          These balls are saved on this phone but are <b>not</b> in the match. They will not be lost.
          {locked ? ' The correction window has closed: ask an administrator to unlock the match (a reason of at least 10 characters), then press Send again.' : ''}
        </Alert>
        {items.length === 0 && <Typography>Nothing is set aside.</Typography>}
        <Stack spacing={1}>
          {items.map((i) => (
            <Box key={i.n} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1 }} data-testid="set-aside-item">
              <Typography fontWeight={700}>{i.label}</Typography>
              <Typography variant="body2" color="text.secondary">Set aside because {i.reason ? reasonText[i.reason] : 'of an unknown reason'}.{i.detail ? ` (${i.detail})` : ''}</Typography>
              <Button size="small" color="warning" onClick={() => setAsking(i)} data-testid="set-aside-discard">Discard…</Button>
            </Box>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ flexWrap: 'wrap', gap: 1 }}>
        <Button onClick={onExport} data-testid="set-aside-copy">Copy or download</Button>
        <Button onClick={onSendAgain} disabled={!canSendAgain} data-testid="set-aside-send-again">Send again</Button>
        <Button variant="contained" onClick={onClose}>Close</Button>
      </DialogActions>
      {asking && (
        <Dialog open onClose={() => setAsking(null)} maxWidth="xs" fullWidth>
          <DialogTitle>Discard this ball?</DialogTitle>
          <DialogContent><Typography>“{asking.label}” will be deleted from this phone for good. It is not in the match.</Typography></DialogContent>
          <DialogActions>
            <Button onClick={() => setAsking(null)}>Keep it</Button>
            <Button color="warning" variant="contained" data-testid="set-aside-confirm-discard" onClick={() => { onDiscard(asking.n); setAsking(null); }}>Discard it</Button>
          </DialogActions>
        </Dialog>
      )}
    </Dialog>
  );
};

/**
 * "Copy unsent balls". On a phone reached over plain http the clipboard API is not available, so the text is shown to be selected by hand, and a download is offered
 * as well. It lists everything the phone holds that the server does not, waiting or set aside.
 */
export const UnsentExportDialog = ({ items, onClose }: { items: QueueItem[]; onClose: () => void }) => {
  const { text, json } = exportItems(items);
  const [copied, setCopied] = useState<string | null>(null);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${text}\n\n${json}`);
      setCopied('Copied.');
    } catch {
      setCopied('This browser would not copy it. Select the text below and copy it by hand, or download it.');
    }
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([`${text}\n\n${json}\n`], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'unsent-balls.txt';
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid="unsent-export">
      <DialogTitle>Unsent balls</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 1 }}>Keep a copy until they are in the match. The phone can lose them only if the browser's site data is cleared.</Typography>
        <TextField multiline fullWidth minRows={6} maxRows={12} value={`${text}\n\n${json}`} slotProps={{ htmlInput: { readOnly: true, 'data-testid': 'unsent-text', style: { fontFamily: 'monospace', fontSize: 12 } } }} />
        {copied && <Typography variant="caption" data-testid="copy-result">{copied}</Typography>}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void copy()} data-testid="unsent-copy">Copy</Button>
        <Button onClick={download} data-testid="unsent-download">Download</Button>
        <Button variant="contained" onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
};
