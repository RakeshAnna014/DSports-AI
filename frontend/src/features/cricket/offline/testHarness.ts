import { eligibleBowlers, logFrom, newLog, stateFingerprint, stepCommand, stepUndo, type LogEntry, type MatchSetup, type ScoreCommand, type ScoringLog } from '../engine';
import { LIONS, Rng, TIGERS, lion, tiger } from '../engine/tsVectors';
import { standardRules } from '../engine/rules';
import * as queue from './queue';
import { loadQueue, newQueueState, saveEngineState } from './load';
import { MemoryStorage } from './storage';
import type { QueueItem, QueueState, ServerView, Step } from './types';

/**
 * Test-only: a phone (queue state + in-memory storage) and a fake server that keeps the same rules as the real one for the parts the queue depends on:
 * idempotency by client event id BEFORE the seq check, `expectedSeq` against the latest seq, the engine, the 24 hour lock, and the scorer being removed.
 */
export const USER = '00000000-0000-0000-0000-0000000000a1';
export const MATCH = '00000000-0000-0000-0000-0000000000b1';

export const SETUP: MatchSetup = {
  overs: 3,
  rules: { ...standardRules(), playersPerSide: 4 },
  teamA: LIONS,
  teamB: TIGERS,
  battingFirst: LIONS,
  xiA: [1, 2, 3, 4].map(lion),
  xiB: [1, 2, 3, 4].map(tiger),
};

export const START: ScoreCommand = { type: 'START_INNINGS', striker: lion(1), nonStriker: lion(2), bowler: tiger(1) };

export type Answer =
  | { status: 200; seq: number; replayed: boolean }
  | { status: 409; kind: 'CONFLICT'; view: ServerView }
  | { status: 409; kind: 'LOCKED'; editableUntil: string }
  | { status: 422; violation: string; message: string }
  | { status: 403 };

export class FakeServer {
  log: ScoringLog;
  locked = false;
  forbidden = false;
  /** Every id the server has applied, in order: the record the "no duplicates, nothing lost" checks read. */
  applied: string[] = [];
  received = 0;

  constructor(setup: MatchSetup, entries: LogEntry[] = []) {
    this.log = entries.length === 0 ? newLog(setup) : logFrom(setup, entries);
  }

  view(): ServerView {
    return { seq: this.log.entries.length, entries: [...this.log.entries], engineVersion: 1, stateFingerprint: stateFingerprint(this.log) };
  }

  /** A command from somebody else (another scorer, an administrator): an ordinary event with the next seq. */
  foreign(command: ScoreCommand): boolean {
    const step = stepCommand(this.log, command, `foreign-${this.log.entries.length + 1}`);
    if (step.result.accepted) {
      this.log = step.log;
      this.applied.push(`foreign-${this.log.entries.length}`);
    }
    return step.result.accepted;
  }

  /** An administrator reopens the match: an ordinary event with the next seq (and the window is open again). */
  unlock(): void {
    this.locked = false;
    this.log = { ...this.log, entries: [...this.log.entries, { seq: this.log.entries.length + 1, kind: 'ADMIN_UNLOCK', clientEventId: null }] };
    this.applied.push(`unlock-${this.log.entries.length}`);
  }

  receive(item: QueueItem): Answer {
    this.received++;
    if (this.forbidden) {
      return { status: 403 };
    }
    const seen = this.log.entries.find((e) => e.clientEventId === item.clientEventId);
    if (seen !== undefined) {
      return { status: 200, seq: seen.seq, replayed: true };
    }
    if (this.locked) {
      return { status: 409, kind: 'LOCKED', editableUntil: '2026-11-24T10:00:00Z' };
    }
    if (item.expectedSeq !== this.log.entries.length) {
      return { status: 409, kind: 'CONFLICT', view: this.view() };
    }
    const step = item.kind === 'COMMAND' ? stepCommand(this.log, item.command!, item.clientEventId) : stepUndo(this.log, item.mode!, item.clientEventId);
    if (!step.result.accepted) {
      return { status: 422, violation: step.result.violation, message: step.result.message };
    }
    this.log = step.log;
    this.applied.push(item.clientEventId);
    return { status: 200, seq: step.result.seq, replayed: false };
  }
}

export type Fault = 'none' | 'network' | 'lostAnswer';

/** A phone: the queue state, the storage behind it, and the sender loop the screen will run (gate 2f-4), minimal and synchronous enough to test. */
export class Phone {
  storage = new MemoryStorage();
  state: QueueState;
  private idCounter = 0;
  private tick = 0;
  /** Everything ever tapped that the queue took, by id: what "nothing is lost" is checked against. */
  taken: string[] = [];

  constructor(
    readonly server: FakeServer,
    state?: QueueState,
  ) {
    const view = server.view();
    this.state = state ?? newQueueState({ matchId: MATCH, userId: USER, setup: SETUP, entries: view.entries, engineVersion: 1, stateFingerprint: view.stateFingerprint });
  }

  readonly newId = (): string => `id-${String(++this.idCounter).padStart(6, '0')}`;
  now = (): string => `t${String(++this.tick).padStart(6, '0')}`;

  /** Commits the step's mutations, then (and only then) adopts its state: the rule "a ball is on the screen only after it is saved". */
  async commit(step: Step): Promise<Step> {
    if (step.refused === null && step.mutations.length > 0) {
      await this.storage.commit(step.mutations);
    }
    if (step.refused === null) {
      this.state = step.state;
    }
    return step;
  }

  async tap(command: ScoreCommand): Promise<Step> {
    const step = await this.commit(queue.tap(this.state, { kind: 'COMMAND', command }, { now: this.now(), newId: this.newId }));
    if (step.refused === null) {
      this.taken.push(this.state.items[this.state.items.length - 1].clientEventId);
    }
    return step;
  }

  async undo(mode: 'BALL' | 'COMMAND' = 'BALL'): Promise<Step> {
    const step = await this.commit(queue.tap(this.state, { kind: 'UNDO', mode }, { now: this.now(), newId: this.newId }));
    if (step.refused === null) {
      this.taken.push(this.state.items[this.state.items.length - 1].clientEventId);
    }
    return step;
  }

  /** Sends the next item, once, the way the sender will. Returns what happened. */
  async sendNext(fault: Fault = 'none'): Promise<string> {
    const item = queue.nextToSend(this.state);
    if (item === null) {
      return 'nothing';
    }
    await this.commit(queue.sendStarted(this.state, item.n, this.now()));
    if (fault === 'network') {
      await this.commit(queue.sendFailed(this.state, item.n, 'no network'));
      return 'network';
    }
    const answer = this.server.receive(item);
    if (fault === 'lostAnswer') {
      await this.commit(queue.sendFailed(this.state, item.n, 'the answer was lost'));
      return 'lost';
    }
    switch (answer.status) {
      case 200:
        await this.commit(queue.confirm(this.state, item.n, answer.seq, answer.replayed, this.now()));
        return answer.replayed ? 'replayed' : 'confirmed';
      case 409:
        if (answer.kind === 'CONFLICT') {
          await this.commit(queue.syncWithServer(this.state, answer.view, this.now(), item.n));
          return this.state.blocked?.kind === 'CONFLICT' ? 'conflict' : 'resynced';
        }
        await this.commit(queue.locked(this.state, item.n, answer.editableUntil, this.now()));
        return 'locked';
      case 422:
        await this.commit(queue.rejectedByServer(this.state, item.n, answer.violation, answer.message, this.now()));
        return 'rejected';
      case 403:
        await this.commit(queue.forbidden(this.state, this.now()));
        return 'forbidden';
    }
  }

  /** The screen's first open: the server's log is saved so that a reload can rebuild the queue. */
  async open(): Promise<void> {
    await this.storage.commit([saveEngineState(this.state, this.now())]);
  }

  /** The screen's open: read the server's log and reconcile. */
  async sync(): Promise<void> {
    await this.commit(queue.syncWithServer(this.state, this.server.view(), this.now()));
  }

  /** A page reload: the state is rebuilt from storage alone. */
  async reload(): Promise<QueueState> {
    const result = await loadQueue(this.storage, { matchId: MATCH, userId: USER }, this.now());
    if (!result.ok) {
      throw new Error(`reload failed: ${result.reason}`);
    }
    if (result.mutations.length > 0) {
      await this.storage.commit(result.mutations);
    }
    this.state = result.state;
    return result.state;
  }
}

/** A command the engine will probably accept in this log (and sometimes one it will not), picked with a seeded generator. */
export function plausible(r: Rng, log: ScoringLog): ScoreCommand {
  const score = log.score;
  const current = score.innings.length === 0 ? null : score.innings[score.innings.length - 1];
  if (current === null || current.status === 'COMPLETE') {
    const batting = score.innings.length === 0 ? SETUP.battingFirst : TIGERS;
    const batters = batting === LIONS ? SETUP.xiA : SETUP.xiB;
    const bowlers = batting === LIONS ? SETUP.xiB : SETUP.xiA;
    return { type: 'START_INNINGS', striker: batters[0], nonStriker: batters[1], bowler: bowlers[0] };
  }
  if (current.needsBatter) {
    const next = current.battingXi.find((p) => p !== current.atBattingEnd && p !== current.atBowlersEnd && current.batters.find((b) => b.playerId === p) === undefined);
    return { type: 'SELECT_BATTER', batterId: next ?? current.battingXi[0] };
  }
  if (current.needsBowler) {
    return { type: 'SELECT_BOWLER', bowlerId: eligibleBowlers(current)[0] ?? current.bowlingXi[0] };
  }
  const p = r.int(100);
  if (p < 8) {
    return { type: 'DELIVER', delivery: { kind: 'WIDE', runs: r.int(2), runsType: null, boundary: false, dismissal: null } };
  }
  if (p < 14) {
    return { type: 'DELIVER', delivery: { kind: 'NO_BALL', runs: r.int(2), runsType: 'BAT', boundary: false, dismissal: null } };
  }
  if (p < 24) {
    return { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0, runsType: 'BAT', boundary: false, dismissal: { type: 'BOWLED', batterOut: null, end: null, fielderId: null } } };
  }
  if (p < 30) {
    return { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 4, runsType: 'BAT', boundary: true, dismissal: null } };
  }
  return { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: r.int(4), runsType: 'BAT', boundary: false, dismissal: null } };
}
