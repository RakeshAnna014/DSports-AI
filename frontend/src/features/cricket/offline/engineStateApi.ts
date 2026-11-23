import { logFrom, parseCommand, stateFingerprint, type LogEntry, type MatchSetup, type Waiver } from '../engine';
import type { ServerView } from './types';

/**
 * Reads the answer of `GET /api/v1/matches/{id}/scoring/engine-state` (step 2f-3) into the engine's own types. It is strict: an answer that does not have the shape
 * the Java route writes is refused with a reason, never half-read, because a phone that predicts balls from a misread log would queue balls the server refuses.
 * The Java build writes sample answers (`testdata/cricket-engine/server/`) and `engineStateApi.test.ts` reads them here, folds the log with the TypeScript engine
 * and compares the fingerprint, so this parser and the route cannot drift apart unnoticed.
 */
export class EngineStateFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EngineStateFormatError';
  }
}

export interface EngineStateAnswer {
  matchId: string;
  engineVersion: number;
  seq: number;
  stateFingerprint: string;
  /** False while the toss or a playing XI is missing: nothing can be scored, so nothing can be queued. */
  ready: boolean;
  setup: MatchSetup;
  entries: LogEntry[];
}

type Raw = Record<string, unknown>;
const isRaw = (v: unknown): v is Raw => typeof v === 'object' && v !== null && !Array.isArray(v);

function need<T>(ok: boolean, what: string, value: T): T {
  if (!ok) {
    throw new EngineStateFormatError(what);
  }
  return value;
}

const text = (v: unknown, what: string): string => need(typeof v === 'string' && v.length > 0, `${what} is missing`, v as string);
const whole = (v: unknown, what: string): number => need(typeof v === 'number' && Number.isSafeInteger(v) && v >= 0, `${what} is not a whole number`, v as number);
const list = (v: unknown, what: string): unknown[] => need(Array.isArray(v), `${what} is not a list`, v as unknown[]);
const ids = (v: unknown, what: string): string[] => list(v, what).map((x) => text(x, `${what} has an entry that is not an id`).toLowerCase());

function entryOf(raw: unknown, index: number): LogEntry {
  need(isRaw(raw), `entry ${index + 1} is not an object`, null);
  const e = raw as Raw;
  const seq = whole(e.seq, `entry ${index + 1} seq`);
  need(seq === index + 1, `entry ${index + 1} has seq ${seq}: the log must be numbered 1, 2, 3 without gaps`, null);
  // Only the asker's own entries carry their client event id; the others are null.
  const clientEventId = e.clientEventId === null || e.clientEventId === undefined ? null : text(e.clientEventId, `entry ${seq} clientEventId`).toLowerCase();
  switch (e.kind) {
    case 'COMMAND': {
      const waivers = list(e.waivers ?? [], `entry ${seq} waivers`).map((w): Waiver => {
        need(isRaw(w), `entry ${seq} has a waiver that is not an object`, null);
        const x = w as Raw;
        return { over: whole(x.over, `entry ${seq} waiver over`), bowlerId: text(x.bowlerId, `entry ${seq} waiver bowler`).toLowerCase(), kind: x.kind as Waiver['kind'] };
      });
      try {
        return { seq, kind: 'COMMAND', command: parseCommand(e.command), clientEventId, waivers };
      } catch (error) {
        throw new EngineStateFormatError(`entry ${seq} has a command this phone cannot read: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
    case 'UNDO':
      return { seq, kind: 'UNDO', voids: list(e.voids, `entry ${seq} voids`).map((v) => whole(v, `entry ${seq} void`)), clientEventId };
    case 'ADMIN_UNLOCK':
      return { seq, kind: 'ADMIN_UNLOCK', clientEventId };
    default:
      throw new EngineStateFormatError(`entry ${seq} has an unknown kind`);
  }
}

export function parseEngineState(json: unknown): EngineStateAnswer {
  need(isRaw(json), 'the answer is not an object', null);
  const o = json as Raw;
  need(isRaw(o.setup), 'setup is missing', null);
  const s = o.setup as Raw;
  need(isRaw(s.rules), 'setup.rules is missing', null);
  const r = s.rules as Raw;
  const setup: MatchSetup = {
    overs: whole(s.overs, 'setup.overs'),
    rules: {
      ballsPerOver: whole(r.ballsPerOver, 'rules.ballsPerOver'),
      playersPerSide: whole(r.playersPerSide, 'rules.playersPerSide'),
      maxOversPerBowler: r.maxOversPerBowler === null || r.maxOversPerBowler === undefined ? null : whole(r.maxOversPerBowler, 'rules.maxOversPerBowler'),
      widePenalty: whole(r.widePenalty, 'rules.widePenalty'),
      noBallPenalty: whole(r.noBallPenalty, 'rules.noBallPenalty'),
      freeHitEnabled: need(typeof r.freeHitEnabled === 'boolean', 'rules.freeHitEnabled is not true or false', r.freeHitEnabled as boolean),
      lastManStands: need(typeof r.lastManStands === 'boolean', 'rules.lastManStands is not true or false', r.lastManStands as boolean),
    },
    teamA: text(s.teamA, 'setup.teamA').toLowerCase(),
    teamB: text(s.teamB, 'setup.teamB').toLowerCase(),
    // Before the toss there is no batting side: the phone cannot score, and `ready` says so.
    battingFirst: typeof s.battingFirst === 'string' ? s.battingFirst.toLowerCase() : '',
    xiA: ids(s.xiA, 'setup.xiA'),
    xiB: ids(s.xiB, 'setup.xiB'),
  };
  const entries = list(o.entries, 'entries').map(entryOf);
  const seq = whole(o.seq, 'seq');
  need(seq === entries.length, `seq is ${seq} but the log has ${entries.length} entries`, null);
  return {
    matchId: text(o.matchId, 'matchId').toLowerCase(),
    engineVersion: whole(o.engineVersion, 'engineVersion'),
    seq,
    stateFingerprint: text(o.stateFingerprint, 'stateFingerprint'),
    ready: need(typeof o.ready === 'boolean', 'ready is not true or false', o.ready as boolean),
    setup,
    entries,
  };
}

/** What the queue needs from the answer. */
export const toServerView = (a: EngineStateAnswer): ServerView => ({ seq: a.seq, entries: a.entries, engineVersion: a.engineVersion, stateFingerprint: a.stateFingerprint });

/** The fingerprint of the answer's own log, worked out with this phone's engine: what the self-check compares with `stateFingerprint`. */
export const ownFingerprint = (a: EngineStateAnswer): string => stateFingerprint(logFrom(a.setup, a.entries));
