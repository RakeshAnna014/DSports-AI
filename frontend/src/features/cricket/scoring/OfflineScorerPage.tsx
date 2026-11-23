import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { scoringApi } from '@/api/cricketScoring';
import type { CommandBody, DeliveryBody, DeliveryKind } from '@/types/cricketScoring';
import { parseCommand } from '../engine';
import { nameBookFrom } from '../offline/localView';
import * as queue from '../offline/queue';
import { QUEUE_CAP } from '../offline/types';
import type { OfflineScorer } from '../offline/useOfflineScorer';
import { ConflictSheet, RejectedSheet, SetAsideSheet, UnsentExportDialog } from '../offline/UnsentSheets';
import { ShareScorecardButton } from '../public/ShareScorecardButton';
import { BallPad } from './BallPad';
import { ChooserDialog } from './ChooserDialog';
import { MoreDialog } from './MoreDialog';
import { InningsCard, ScoreSummary } from './ScoreSummary';
import { StartInningsForm } from './StartInningsForm';
import { WicketSheet } from './WicketSheet';
import { ruleMessage } from './ruleMessages';
import { formatLeft, minutesUntil, useNow } from './useNow';

/**
 * The scorer's screen when the phone can keep scoring with no signal. The balls go into the on-phone queue (saved before they are shown) and are sent in order
 * when there is a network; the pad, the scoreboard and the undo button read a view the phone builds from its own copy of the rules, so they work with no signal.
 * Every state the scorer can be in is said in words on this screen: working offline, n waiting, sending, retrying, needs sign-in, somebody else scored,
 * the server refused a ball, the match is locked, balls saved but not in the match, offline scoring switched off, and "this is not the tab that is scoring".
 */
const OfflineScorerPage = ({ lease, offline }: { lease: { owner: boolean; recheck: () => boolean }; offline: OfflineScorer }) => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { state, view, sender } = offline;
  const readOnly = !lease.owner;
  const now = useNow();
  const match = useQuery({ queryKey: ['match', id], queryFn: () => cricketApi.matches.get(id) });
  const [online, setOnline] = useState(() => typeof navigator === 'undefined' || navigator.onLine !== false);
  const [notice, setNotice] = useState<string | null>(null);
  const [wicketKind, setWicketKind] = useState<DeliveryKind | null>(null);
  const [more, setMore] = useState(false);
  const [confirmUndo, setConfirmUndo] = useState(false);
  const [sheet, setSheet] = useState<'set-aside' | 'export' | null>(null);
  const [dismissedBlock, setDismissedBlock] = useState<string | null>(null);

  // The server's own view is only for names and the correction window (the screen shows the phone's view), so it is asked for when there is a network and nothing waits.
  const waiting = state ? queue.chainOf(state.items).length : 0;
  const server = useQuery({ queryKey: ['scoring', id], queryFn: () => scoringApi.state(id), refetchInterval: online && waiting === 0 ? 20000 : false, retry: false });
  const noteServerView = offline.noteServerView;
  const noteMatch = offline.noteMatch;
  // Names and the window are folded in once the queue exists (the match and the server's view can arrive before it), and again whenever they change.
  const queueReady = state !== null;
  useEffect(() => {
    if (queueReady && server.data && waiting === 0) {
      noteServerView(server.data);
    }
  }, [queueReady, server.data, waiting, noteServerView]);
  useEffect(() => {
    if (queueReady && match.data) {
      noteMatch(match.data);
    }
  }, [queueReady, match.data, noteMatch]);
  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);

  const m = match.data;
  if (!state || !view || !m) {
    return <Skeleton variant="rounded" height={240} />;
  }

  const names = nameBookFrom(view);
  const current = view.innings[view.innings.length - 1];
  const status = view.matchStatus;
  const finished = status === 'COMPLETED' || status === 'ABANDONED' || status === 'NO_RESULT';
  const chain = queue.chainOf(state.items);
  const aside = state.items.filter((i) => i.status === 'SET_ASIDE');
  const blocked = state.blocked;
  const level = queue.queueLevel(state);
  const locked = view.locked || blocked?.kind === 'LOCKED';
  const padOff = readOnly || blocked !== null || !state.offline.on || locked || level === 'FULL' || sender.phase === 'storage-failed';
  const sendingIndex = chain.findIndex((i) => i.status === 'IN_FLIGHT') + 1;
  const minutesLeft = minutesUntil(view.editableUntil, now);
  const lockWarning = !locked && chain.length > 0 && minutesLeft !== null && minutesLeft <= 30;

  const take = async (command: CommandBody | null, mode?: 'BALL' | 'COMMAND') => {
    setNotice(null);
    try {
      const refusal = mode ? await offline.undo(mode) : await offline.tap(parseCommand(command));
      if (refusal !== null) {
        setNotice(refusal.violation && refusal.code === 'RULE' ? ruleMessage(refusal.violation, refusal.message) : refusal.message);
      }
    } catch {
      setNotice('That ball could not be read, so it was not counted.');
    }
  };
  const sendCommand = (command: CommandBody) => void take(command);
  const sendBall = (delivery: DeliveryBody) => {
    setWicketKind(null);
    void take({ type: 'DELIVER', delivery });
  };
  const sendUndo = () => {
    setConfirmUndo(false);
    void take(null, 'BALL');
  };

  const startForm = (battingTeamId: string, title: string) => (
    <StartInningsForm key={title} match={m} battingTeamId={battingTeamId} title={title} busy={padOff}
      onStart={(striker, nonStriker, bowler) => sendCommand({ type: 'START_INNINGS', striker, nonStriker, bowler })} />
  );

  const phaseText =
    sender.phase === 'sending' && chain.length > 0 ? `Sending ${Math.max(1, sendingIndex)} of ${chain.length}…`
      : sender.phase === 'retrying' ? `Waiting for the network. Next try ${sender.nextAttemptAt ? `in ${Math.max(0, Math.ceil((sender.nextAttemptAt - now) / 1000))} s` : 'soon'}.`
        : sender.phase === 'lease-lost' ? 'Another tab is scoring this match.'
          : null;

  return (
    <Box sx={{ maxWidth: 560, mx: 'auto' }} data-testid="scorer-page">
      <Button size="small" onClick={() => navigate(`/matches/${id}/setup`)} sx={{ mb: 1 }}>Back to setup</Button>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="h5" component="h1">{m.teamA.teamName} vs {m.teamB.teamName}</Typography>
        <Chip size="small" label={status.replace('_', ' ').toLowerCase()} data-testid="match-status" />
        {!online && <Chip size="small" color="warning" label="Offline: scoring continues, saved on this phone" data-testid="chip-offline" />}
        {chain.length > 0 && <Chip size="small" color="warning" variant="outlined" label={`${chain.length} unsent`} data-testid="chip-unsent" />}
        {aside.length > 0 && <Chip size="small" color="info" label={`${aside.length} set aside`} onClick={() => setSheet('set-aside')} data-testid="chip-set-aside" />}
      </Stack>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }} data-testid="keep-open-hint">Keep this page open while scoring offline.</Typography>
      {view.innings.length > 0 && <Box sx={{ mb: 1 }}><ShareScorecardButton matchId={id} title={`${m.teamA.teamName} vs ${m.teamB.teamName}`} /></Box>}

      {readOnly && (
        <Alert severity="info" sx={{ mb: 1 }} data-testid="other-tab-banner">
          This match is open for scoring in another tab of this browser, so this tab is read-only{chain.length > 0 ? ` (${chain.length} waiting on this phone)` : ''}. Close the other tab to score here.
        </Alert>
      )}
      {chain.length > 0 && !readOnly && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="waiting-banner"
          action={<Stack direction="row" spacing={0.5}>
            {sender.phase === 'retrying' && <Button color="inherit" size="small" onClick={offline.retryNow} data-testid="retry-now">Retry now</Button>}
            <Button color="inherit" size="small" onClick={() => setSheet('export')} data-testid="open-export">Copy unsent balls</Button>
          </Stack>}>
          {chain.length === 1 ? '1 change is' : `${chain.length} changes are`} waiting on this phone and not on the server yet. People following the match see the score up to the last ball the server has.
          {phaseText ? ` ${phaseText}` : ''}
        </Alert>
      )}
      {level === 'WARN' && <Alert severity="warning" sx={{ mb: 1 }} data-testid="warn-150">{state.items.length} changes are waiting on this phone. Get a signal soon so they can be sent.</Alert>}
      {level === 'FULL' && (
        <Alert severity="error" sx={{ mb: 1 }} data-testid="full-banner" action={<Button color="inherit" size="small" onClick={offline.retryNow}>Retry sending now</Button>}>
          This phone is holding {QUEUE_CAP} unsent changes, the most it will keep. Get a signal so they can be sent, then carry on. Nothing already scored is lost.
        </Alert>
      )}
      {sender.phase === 'needs-sign-in' && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="sign-in-banner" action={<Button color="inherit" size="small" onClick={() => navigate('/login')}>Sign in</Button>}>
          You need to sign in again before the waiting balls can be sent. They are saved on this phone and will wait.
        </Alert>
      )}
      {sender.phase === 'storage-failed' && (
        <Alert severity="error" sx={{ mb: 1 }} data-testid="storage-banner">This phone could not save a change, so scoring is paused. Nothing already saved is lost. {sender.lastError}</Alert>
      )}
      {!state.offline.on && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="offline-off-banner">
          Offline scoring is off on this phone ({state.offline.reason === 'ENGINE_VERSION_UNSUPPORTED' ? 'the match uses newer rules than this page knows: reload it' : state.offline.reason === 'FINGERPRINT_MISMATCH' ? 'this phone and the server disagree about the score: reload the page' : 'the two copies of the rules disagreed'}).
          {chain.length > 0 ? ` The ${chain.length} waiting ball${chain.length === 1 ? ' is' : 's are'} kept and can still be sent. New balls are taken again once they are sent.` : ''}
        </Alert>
      )}
      {locked && (
        <Alert severity="info" sx={{ mb: 1 }} data-testid="locked-banner">
          The correction window{blocked?.kind === 'LOCKED' && blocked.editableUntil ? ` closed at ${new Date(blocked.editableUntil).toLocaleString()}` : ' has closed'}. An administrator can unlock this match
          {aside.length > 0 ? `; ${aside.length} ball${aside.length === 1 ? '' : 's'} saved on this phone ${aside.length === 1 ? 'is' : 'are'} waiting for that` : ''}.
          {aside.length > 0 && <> <Button size="small" onClick={() => setSheet('set-aside')}>See them</Button></>}
        </Alert>
      )}
      {blocked?.kind === 'FORBIDDEN' && <Alert severity="error" sx={{ mb: 1 }} data-testid="forbidden-banner">You can no longer score this match, or it no longer exists. The balls on this phone are saved, not lost.</Alert>}
      {lockWarning && <Alert severity="warning" sx={{ mb: 1 }} data-testid="lock-warning">The correction window closes in {formatLeft(Math.max(1, minutesLeft ?? 1))} and balls are still waiting. Get a signal soon.</Alert>}
      {notice && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setNotice(null)} data-testid="scoring-notice">{notice}</Alert>}

      <Stack spacing={1.5}>
        {status === 'TOSS_DONE' && (m.battingFirstTeamId ? startForm(m.battingFirstTeamId, 'Start the first innings') : <Alert severity="warning">Record the toss and both playing XIs on the setup page first.</Alert>)}
        {status === 'INNINGS_BREAK' && current && (
          <>
            <Paper variant="outlined" sx={{ p: 2 }} data-testid="innings-break">
              <Typography variant="h6">Innings break</Typography>
              <Typography>{current.battingTeam.name} made {current.runs}/{current.wickets} in {current.overs} overs.</Typography>
              <Typography fontWeight={700}>{current.bowlingTeam.name} need {current.runs + 1} to win.</Typography>
            </Paper>
            {startForm(current.bowlingTeam.id, 'Start the second innings')}
          </>
        )}
        {status === 'IN_PROGRESS' && current && (
          <>
            <Box sx={chain.length > 0 ? { border: '2px dashed', borderColor: 'warning.main', borderRadius: 1, p: 0.5 } : undefined} data-testid="scoreboard">
              <ScoreSummary view={view} innings={current} />
              {chain.length > 0 && <Typography variant="caption" color="warning.main" sx={{ px: 1 }} data-testid="unconfirmed-note">Dashed: includes {chain.length} ball{chain.length === 1 ? '' : 's'} not on the server yet.</Typography>}
            </Box>
            <BallPad disabled={padOff || view.needs !== null} onDeliver={sendBall} onWicket={setWicketKind} />
            <Button variant="outlined" size="small" onClick={() => setMore(true)} disabled={padOff} data-testid="more">More</Button>
          </>
        )}
        {finished && <Paper variant="outlined" sx={{ p: 2 }} data-testid="result-panel"><Typography variant="h5" data-testid="result-text">{view.result?.text ?? 'Finished'}</Typography></Paper>}
        {chain.length > 0 && (
          <Paper variant="outlined" sx={{ p: 1.5, borderStyle: 'dashed' }} data-testid="unconfirmed-list">
            <Typography variant="subtitle2">Not on the server yet</Typography>
            {chain.slice(0, 8).map((i) => (
              <Typography key={i.n} variant="body2" color="text.secondary" data-testid="unconfirmed-item">{i.status === 'IN_FLIGHT' ? '↑ ' : '· '}{i.label}</Typography>
            ))}
            {chain.length > 8 && <Typography variant="body2" color="text.secondary">…and {chain.length - 8} more</Typography>}
          </Paper>
        )}
        {view.innings.map((innings) => <InningsCard key={innings.number} innings={innings} />)}
        <Button variant="outlined" color="inherit" disabled={!view.canUndo.allowed || padOff} onClick={() => (view.canUndo.crossesInnings ? setConfirmUndo(true) : sendUndo())} data-testid="undo">
          {view.canUndo.allowed && view.canUndo.describe ? view.canUndo.describe : 'Nothing to undo'}
        </Button>
        {view.recentEvents.length > 0 && (
          <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="recent-events">
            <Typography variant="subtitle2">Latest</Typography>
            {[...view.recentEvents].reverse().slice(0, 6).map((e) => <Typography key={e.seq} variant="body2" color="text.secondary">{e.seq}. {e.text} ({e.by.name})</Typography>)}
          </Paper>
        )}
        {state.items.length > 0 && <Button size="small" onClick={() => setSheet('export')}>Copy or download unsent balls</Button>}
      </Stack>

      {status === 'IN_PROGRESS' && view.needs === 'BATTER' && !padOff && (
        <ChooserDialog title="Who bats next?" blocking busy={padOff} choices={view.eligibleBatters.map((b) => ({ id: b.player.id, label: b.player.name, detail: b.note }))}
          onChoose={(batterId) => sendCommand({ type: 'SELECT_BATTER', batterId })} />
      )}
      {status === 'IN_PROGRESS' && view.needs === 'BOWLER' && !padOff && (
        <ChooserDialog title="Who bowls the next over?" blocking busy={padOff}
          notice={view.waiverRequired ? 'Everyone left is at the over limit. Whoever you choose bowls anyway, and this is noted on the scorecard.' : null}
          choices={view.bowlers.map((b) => ({ id: b.player.id, label: b.player.name, detail: `${b.overs} ov, ${b.creditedOvers} of ${b.limit} overs${b.why === 'BOWLED_LAST_OVER' ? ' · bowled the last over' : b.why === 'AT_LIMIT' ? ' · at the limit' : ''}`, disabled: !b.canBowlNext }))}
          onChoose={(bowlerId) => sendCommand({ type: 'SELECT_BOWLER', bowlerId })} />
      )}
      {wicketKind && current && <WicketSheet view={view} innings={current} kind={wicketKind} onCancel={() => setWicketKind(null)} onConfirm={sendBall} />}
      {more && current && <MoreDialog view={view} innings={current} onClose={() => setMore(false)} onSend={sendCommand} />}

      <Dialog open={confirmUndo} onClose={() => setConfirmUndo(false)} fullWidth maxWidth="xs">
        <DialogTitle>Undo?</DialogTitle>
        <DialogContent><Typography>{view.canUndo.describe}. This reopens an innings or a finished match and clears the result until it is scored again.</Typography></DialogContent>
        <DialogActions><Button onClick={() => setConfirmUndo(false)}>Keep it</Button><Button variant="contained" color="warning" onClick={sendUndo} data-testid="confirm-undo">Undo</Button></DialogActions>
      </Dialog>

      {blocked?.kind === 'CONFLICT' && dismissedBlock !== 'CONFLICT' && (
        <ConflictSheet state={state} names={names} onNotNow={() => setDismissedBlock('CONFLICT')}
          onRebase={() => { setDismissedBlock(null); void offline.apply((s, at) => queue.rebase(s, at)); }}
          onKeepTheirs={() => { setDismissedBlock(null); void offline.apply((s, at) => queue.keepTheirs(s, at)); }} />
      )}
      {blocked?.kind === 'CONFLICT' && dismissedBlock === 'CONFLICT' && (
        <Alert severity="warning" sx={{ position: 'sticky', bottom: 8, mt: 1 }} data-testid="conflict-reminder" action={<Button color="inherit" size="small" onClick={() => setDismissedBlock(null)}>Decide</Button>}>
          Someone else scored. Your {chain.length} unsent ball{chain.length === 1 ? ' is' : 's are'} on hold until you decide.
        </Alert>
      )}
      {blocked?.kind === 'REJECTED' && dismissedBlock !== 'REJECTED' && (
        <RejectedSheet state={state} onNotNow={() => setDismissedBlock('REJECTED')} onSetAside={(n) => { setDismissedBlock(null); void offline.apply((s, at) => queue.setAsideFrom(s, n, 'REJECTED_BY_SERVER', at)); }} />
      )}
      {sheet === 'set-aside' && (
        <SetAsideSheet items={aside} locked={locked} canSendAgain={aside.some((i) => i.reason !== 'OTHER_ACCOUNT' && i.reason !== 'CORRUPTED')} onClose={() => setSheet(null)} onExport={() => setSheet('export')}
          onSendAgain={() => { setSheet(null); void offline.apply((s, at) => queue.sendAgain(s, at)); }} onDiscard={(n) => void offline.apply((s, at) => queue.discard(s, n, at))} />
      )}
      {sheet === 'export' && <UnsentExportDialog items={state.items} onClose={() => setSheet(null)} />}
    </Box>
  );
};

export default OfflineScorerPage;
