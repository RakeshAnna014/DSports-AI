import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { AxiosError } from 'axios';
import { cricketApi } from '@/api/cricket';
import { classifyScoringError, scoringApi } from '@/api/cricketScoring';
import { ErrorState } from '@/components/common/States';
import type { CommandBody, DeliveryBody, DeliveryKind, ScoringConflict } from '@/types/cricketScoring';
import { ShareScorecardButton } from '../public/ShareScorecardButton';
import { BallPad } from './BallPad';
import { ChooserDialog } from './ChooserDialog';
import { MoreDialog } from './MoreDialog';
import { InningsCard, ScoreSummary } from './ScoreSummary';
import { StartInningsForm } from './StartInningsForm';
import { WicketSheet } from './WicketSheet';
import { newId } from './newId';
import { ruleMessage } from './ruleMessages';
import { clearOutbox, loadOutbox, saveOutbox, type OutboxItem } from './scoringOutbox';
import { formatLeft, minutesUntil, useNow, WARN_MINUTES } from './useNow';
import { useTabLease } from './useTabLease';

const ballLabel = (d: DeliveryBody): string => {
  if (d.dismissal) {
    return 'Wicket';
  }
  const bye = d.runsType === 'BYE' ? 'bye' : d.runsType === 'LEG_BYE' ? 'leg bye' : null;
  if (d.kind === 'WIDE') {
    return d.runs > 0 ? `Wide + ${d.runs}` : 'Wide';
  }
  if (d.kind === 'NO_BALL') {
    return d.runs > 0 ? `No ball + ${d.runs}${bye ? ` ${bye}` : ''}` : 'No ball';
  }
  if (bye) {
    return `${d.runs} ${bye}${d.runs === 1 ? '' : 's'}`;
  }
  return d.runs === 0 ? 'No run' : d.boundary ? (d.runs === 4 ? 'FOUR' : 'SIX') : `${d.runs} run${d.runs === 1 ? '' : 's'}`;
};

/**
 * The scorer's screen. It holds no cricket rules: what is needed next, which dismissals are allowed and who may bat or bowl all come from the server's view.
 * Every change goes through a durable one-slot outbox (saved on the phone, sent with a client event id, retried if the network drops, cleared only when the
 * server answers), so a ball is never lost and never counted twice. A conflict with another scorer is never merged silently: the scorer chooses.
 */
const ScorerPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const match = useQuery({ queryKey: ['match', id], queryFn: () => cricketApi.matches.get(id) });
  const { owner, recheck } = useTabLease(id);
  const readOnly = !owner;
  const state = useQuery({ queryKey: ['scoring', id], queryFn: () => scoringApi.state(id), refetchInterval: readOnly ? 5000 : false });
  const now = useNow();
  const lastExpiryRefetch = useRef(0);

  const [outbox, setOutbox] = useState<OutboxItem | null>(() => loadOutbox(id));
  const [phase, setPhase] = useState<'idle' | 'sending' | 'waiting'>('idle');
  const [conflict, setConflict] = useState<ScoringConflict | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [wicketKind, setWicketKind] = useState<DeliveryKind | null>(null);
  const [more, setMore] = useState(false);
  const [confirmUndo, setConfirmUndo] = useState(false);
  const inFlight = useRef(false);
  const attempts = useRef(0);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resumed = useRef(false);
  const ownerRef = useRef(owner);
  const wasOwner = useRef(owner);
  const outboxRef = useRef<OutboxItem | null>(outbox);
  const conflictRef = useRef<ScoringConflict | null>(null);
  const deliverRef = useRef<(item: OutboxItem) => Promise<void>>(() => Promise.resolve());

  const deliver = useCallback(async (item: OutboxItem) => {
    if (inFlight.current) {
      return;
    }
    // Nothing is sent before the lease is looked at again: this tab may have been frozen in the background while another tab took over.
    if (!recheck()) {
      return;
    }
    inFlight.current = true;
    if (retryTimer.current) {
      clearTimeout(retryTimer.current);
    }
    setPhase('sending');
    try {
      const outcome = item.kind === 'command'
        ? await scoringApi.command(id, { clientEventId: item.clientEventId, expectedSeq: item.expectedSeq, command: item.command })
        : await scoringApi.undo(id, { clientEventId: item.clientEventId, expectedSeq: item.expectedSeq, mode: item.mode });
      attempts.current = 0;
      clearOutbox(id);
      setOutbox(null);
      setPhase('idle');
      setNotice(null);
      queryClient.setQueryData(['scoring', id], outcome.view);
      void queryClient.invalidateQueries({ queryKey: ['match', id] });
      void queryClient.invalidateQueries({ queryKey: ['my-matches'] });
    } catch (err) {
      const failure = classifyScoringError(err);
      if (failure.kind === 'OFFLINE') {
        attempts.current += 1;
        setPhase('waiting');
        retryTimer.current = setTimeout(() => { void deliverRef.current(item); }, Math.min(2000 * attempts.current, 15000));
      } else if (failure.kind === 'CONFLICT') {
        setPhase('idle');
        conflictRef.current = failure.conflict;
        setConflict(failure.conflict);
      } else {
        attempts.current = 0;
        clearOutbox(id);
        setOutbox(null);
        setPhase('idle');
        setNotice(failure.kind === 'REJECTED' ? ruleMessage(failure.violation, failure.message) : failure.message);
        void queryClient.invalidateQueries({ queryKey: ['scoring', id] });
      }
    } finally {
      inFlight.current = false;
    }
  }, [id, queryClient, recheck]);

  useEffect(() => {
    deliverRef.current = deliver;
    outboxRef.current = outbox;
    conflictRef.current = conflict;
    ownerRef.current = owner;
  });

  // This tab takes over from another one: it picks up the ball that tab left saved on the phone and sends it from the current state.
  useEffect(() => {
    if (owner && !wasOwner.current) {
      resumed.current = false;
      setOutbox(loadOutbox(id));
      void queryClient.invalidateQueries({ queryKey: ['scoring', id] });
    }
    wasOwner.current = owner;
  }, [owner, id, queryClient]);

  // A ball saved before a reload is sent only once the current state has loaded, so the older state cannot arrive after the answer and overwrite it.
  const stateLoaded = state.isSuccess;
  useEffect(() => {
    if (owner && !resumed.current && outbox && stateLoaded) {
      resumed.current = true;
      void deliver(outbox);
    }
  }, [owner, outbox, deliver, stateLoaded]);

  useEffect(() => {
    const backOnline = () => {
      if (ownerRef.current && outboxRef.current && !conflictRef.current) {
        void deliverRef.current(outboxRef.current);
      }
    };
    window.addEventListener('online', backOnline);
    return () => {
      window.removeEventListener('online', backOnline);
      if (retryTimer.current) {
        clearTimeout(retryTimer.current);
      }
    };
  }, []);

  const view = state.data;
  const m = match.data;

  // When the clock passes the end of the correction window, ask the server once (and again every 30 seconds if it still says open, in case this clock is fast).
  const editableUntil = view?.editableUntil ?? null;
  const lockedNow = view?.locked ?? false;
  useEffect(() => {
    if (editableUntil && !lockedNow && now >= new Date(editableUntil).getTime() && Date.now() - lastExpiryRefetch.current > 30000) {
      lastExpiryRefetch.current = Date.now();
      void queryClient.invalidateQueries({ queryKey: ['scoring', id] });
    }
  }, [now, editableUntil, lockedNow, id, queryClient]);
  const busy = outbox !== null || readOnly;

  const enqueue = (item: OutboxItem) => {
    if (!recheck()) {
      return;
    }
    saveOutbox(id, item);
    setOutbox(item);
    setNotice(null);
    void deliver(item);
  };
  const sendCommand = (command: CommandBody, label: string) => {
    if (!view || busy) {
      return;
    }
    enqueue({ kind: 'command', clientEventId: newId(), expectedSeq: view.seq, command, label, createdAt: new Date().toISOString() });
  };
  const sendBall = (delivery: DeliveryBody) => {
    setWicketKind(null);
    sendCommand({ type: 'DELIVER', delivery }, ballLabel(delivery));
  };
  const sendUndo = () => {
    if (!view || busy) {
      return;
    }
    setConfirmUndo(false);
    enqueue({ kind: 'undo', clientEventId: newId(), expectedSeq: view.seq, mode: 'BALL', label: view.canUndo.describe ?? 'Undo', createdAt: new Date().toISOString() });
  };

  const applyOnTop = () => {
    if (!conflict || !outbox) {
      return;
    }
    const item = { ...outbox, expectedSeq: conflict.currentSeq } as OutboxItem;
    queryClient.setQueryData(['scoring', id], conflict.view);
    conflictRef.current = null;
    setConflict(null);
    enqueue(item);
  };
  const discardOutbox = (nextView?: ScoringConflict['view']) => {
    clearOutbox(id);
    setOutbox(null);
    conflictRef.current = null;
    setConflict(null);
    setPhase('idle');
    if (nextView) {
      queryClient.setQueryData(['scoring', id], nextView);
    } else {
      void queryClient.invalidateQueries({ queryKey: ['scoring', id] });
    }
  };

  const failedStatus = ((state.error ?? match.error) as AxiosError | null)?.response?.status;
  if (state.isError || match.isError) {
    const body = failedStatus === 403 ? 'Only the match creator or a scorer can score this match.'
      : failedStatus === undefined ? `We can't reach the server right now.${outbox ? ` Your last change (${outbox.label}) is saved on this phone and will be sent when you are back.` : ''}`
        : "We couldn't find that match.";
    return <ErrorState body={body} onRetry={() => { void state.refetch(); void match.refetch(); }} />;
  }
  if (!view || !m) {
    return <Skeleton variant="rounded" height={240} />;
  }

  const current = view.innings[view.innings.length - 1];
  const status = view.matchStatus;
  const finished = status === 'COMPLETED' || status === 'ABANDONED' || status === 'NO_RESULT';
  const locked = view.locked;
  const minutesLeft = minutesUntil(view.editableUntil, now);
  const left = minutesLeft === null ? null : formatLeft(minutesLeft);
  const lockWarning = !locked && minutesLeft !== null && minutesLeft <= WARN_MINUTES;
  const startForm = (battingTeamId: string, title: string) => (
    <StartInningsForm key={title} match={m} battingTeamId={battingTeamId} title={title} busy={busy || locked}
      onStart={(striker, nonStriker, bowler) => sendCommand({ type: 'START_INNINGS', striker, nonStriker, bowler }, 'Start innings')} />
  );

  return (
    <Box sx={{ maxWidth: 560, mx: 'auto' }} data-testid="scorer-page">
      <Button size="small" onClick={() => navigate(`/matches/${id}/setup`)} sx={{ mb: 1 }}>Back to setup</Button>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="h5" component="h1">{m.teamA.teamName} vs {m.teamB.teamName}</Typography>
        <Chip size="small" label={status.replace('_', ' ').toLowerCase()} data-testid="match-status" />
      </Stack>
      {view.innings.length > 0 && (
        <Box sx={{ mb: 1 }}><ShareScorecardButton matchId={id} title={`${m.teamA.teamName} vs ${m.teamB.teamName}`} /></Box>
      )}

      {outbox && !conflict && !readOnly && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="outbox-banner"
          action={<Stack direction="row" spacing={0.5}>
            <Button color="inherit" size="small" onClick={() => void deliver(outbox)} data-testid="retry-now" disabled={phase === 'sending'}>Retry now</Button>
            <Button color="inherit" size="small" onClick={() => discardOutbox()} data-testid="discard-outbox">Discard</Button>
          </Stack>}>
          Saved on this phone: {outbox.label}. {phase === 'waiting' ? 'Waiting for the network. It is sent as soon as you are back online.' : 'Sending…'} Scoring is paused until it is sent, so nothing can be entered on top of it.
        </Alert>
      )}
      {notice && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setNotice(null)} data-testid="scoring-notice">{notice}</Alert>}
      {readOnly && (
        <Alert severity="info" sx={{ mb: 1 }} data-testid="other-tab-banner">
          This match is open for scoring in another tab of this browser, so this tab is read-only. That keeps the two from overwriting each other's saved ball. Close the other tab to score here.
        </Alert>
      )}
      {lockWarning && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="lock-warning">
          This match locks in {formatLeft(Math.max(1, minutesLeft ?? 1))}. Finish your correction or ask an administrator to unlock it.
        </Alert>
      )}
      {locked && (
        <Alert severity="info" sx={{ mb: 1 }} data-testid="locked-banner">
          {finished
            ? 'This match was finished more than 24 hours ago and is locked. An administrator can unlock it.'
            : 'The correction window closed while the match was in progress. An administrator can unlock it.'}
        </Alert>
      )}

      <Stack spacing={1.5}>
        {status === 'TOSS_DONE' && (m.battingFirstTeamId
          ? startForm(m.battingFirstTeamId, 'Start the first innings')
          : <Alert severity="warning">Record the toss and both playing XIs on the setup page first.</Alert>)}

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
            <ScoreSummary view={view} innings={current} />
            <BallPad disabled={busy || locked || view.needs !== null} onDeliver={sendBall} onWicket={setWicketKind} />
            <Button variant="outlined" size="small" onClick={() => setMore(true)} disabled={busy || locked} data-testid="more">More</Button>
          </>
        )}

        {finished && (
          <Paper variant="outlined" sx={{ p: 2 }} data-testid="result-panel">
            <Typography variant="h5" data-testid="result-text">{view.result?.text ?? 'Finished'}</Typography>
          </Paper>
        )}
        {!locked && left && <Typography variant="body2" color="text.secondary" data-testid="time-left">You can correct this match for {left}.</Typography>}

        {view.innings.map((innings) => <InningsCard key={innings.number} innings={innings} />)}

        <Button variant="outlined" color="inherit" disabled={!view.canUndo.allowed || busy || locked} onClick={() => (view.canUndo.crossesInnings ? setConfirmUndo(true) : sendUndo())} data-testid="undo">
          {view.canUndo.allowed && view.canUndo.describe ? view.canUndo.describe : 'Nothing to undo'}
        </Button>

        {view.recentEvents.length > 0 && (
          <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="recent-events">
            <Typography variant="subtitle2">Latest</Typography>
            {[...view.recentEvents].reverse().slice(0, 6).map((e) => (
              <Typography key={e.seq} variant="body2" color="text.secondary">{e.seq}. {e.text} ({e.by.name})</Typography>
            ))}
          </Paper>
        )}
      </Stack>

      {status === 'IN_PROGRESS' && view.needs === 'BATTER' && !readOnly && (
        <ChooserDialog title="Who bats next?" blocking busy={busy} choices={view.eligibleBatters.map((b) => ({ id: b.player.id, label: b.player.name, detail: b.note }))}
          onChoose={(batterId) => sendCommand({ type: 'SELECT_BATTER', batterId }, 'Next batter')} />
      )}
      {status === 'IN_PROGRESS' && view.needs === 'BOWLER' && !readOnly && (
        <ChooserDialog title="Who bowls the next over?" blocking busy={busy}
          notice={view.waiverRequired ? 'Everyone left is at the over limit. Whoever you choose bowls anyway, and this is noted on the scorecard.' : null}
          choices={view.bowlers.map((b) => ({ id: b.player.id, label: b.player.name, detail: `${b.overs} ov, ${b.creditedOvers} of ${b.limit} overs${b.why === 'BOWLED_LAST_OVER' ? ' · bowled the last over' : b.why === 'AT_LIMIT' ? ' · at the limit' : ''}`, disabled: !b.canBowlNext }))}
          onChoose={(bowlerId) => sendCommand({ type: 'SELECT_BOWLER', bowlerId }, 'Next bowler')} />
      )}
      {wicketKind && current && <WicketSheet view={view} innings={current} kind={wicketKind} onCancel={() => setWicketKind(null)} onConfirm={sendBall} />}
      {more && current && <MoreDialog view={view} innings={current} onClose={() => setMore(false)} onSend={sendCommand} />}

      <Dialog open={confirmUndo} onClose={() => setConfirmUndo(false)} fullWidth maxWidth="xs">
        <DialogTitle>Undo?</DialogTitle>
        <DialogContent>
          <Typography>{view.canUndo.describe}. This reopens an innings or a finished match and clears the result until it is scored again.</Typography>
        </DialogContent>
        <DialogActions><Button onClick={() => setConfirmUndo(false)}>Keep it</Button><Button variant="contained" color="warning" onClick={sendUndo} data-testid="confirm-undo">Undo</Button></DialogActions>
      </Dialog>

      {conflict && (
        <Dialog open fullWidth maxWidth="xs" data-testid="conflict-sheet">
          <DialogTitle>Someone else scored while you were away</DialogTitle>
          <DialogContent>
            <Typography sx={{ mb: 1 }} data-testid="conflict-now">Now: {conflict.view.innings[conflict.view.innings.length - 1]
              ? `${conflict.view.innings[conflict.view.innings.length - 1].battingTeam.name} ${conflict.view.innings[conflict.view.innings.length - 1].runs}/${conflict.view.innings[conflict.view.innings.length - 1].wickets} (${conflict.view.innings[conflict.view.innings.length - 1].overs})`
              : 'nothing scored yet'}</Typography>
            <Typography variant="subtitle2">Since you last looked</Typography>
            {conflict.eventsSince.map((e) => <Typography key={e.seq} variant="body2" data-testid="conflict-event">{e.text} ({e.by.name})</Typography>)}
            {outbox && <Alert severity="warning" sx={{ mt: 1.5 }} data-testid="conflict-yours">Your unsent change: {outbox.label}</Alert>}
          </DialogContent>
          <DialogActions>
            <Button onClick={() => discardOutbox(conflict.view)} data-testid="conflict-discard">Discard mine</Button>
            <Button variant="contained" onClick={applyOnTop} data-testid="conflict-apply">Apply mine on top</Button>
          </DialogActions>
        </Dialog>
      )}
    </Box>
  );
};

export default ScorerPage;
