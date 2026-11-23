import type { Delivery, Dismissal, EndMatchReason, EndReason, RetirementType, ScoreCommand, Uuid } from './types';
import { VOCABULARY } from './vocabulary';

/**
 * The command in the exact shape the phone sends to the scoring route (`ScoringRequests.CommandBody`), and back. `parseCommand` mirrors
 * `CommandBody.toCommand`: the same defaults (kind NORMAL, runs 0, runsType BAT except on a wide, boundary false), the same required fields, and the same
 * refusal of anything that is not an integer or a known word. It throws `CommandFormatError`, which the server would answer with a 400 (never a rule violation).
 */
export class CommandFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CommandFormatError';
  }
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

type Raw = Record<string, unknown>;

const isRaw = (v: unknown): v is Raw => typeof v === 'object' && v !== null && !Array.isArray(v);

function uuid(value: unknown, field: string, required: true): Uuid;
function uuid(value: unknown, field: string, required: false): Uuid | null;
function uuid(value: unknown, field: string, required: boolean): Uuid | null {
  if (value === undefined || value === null) {
    if (required) {
      throw new CommandFormatError(`${field} is required for this command`);
    }
    return null;
  }
  const text = typeof value === 'string' ? value.toLowerCase() : '';
  if (!UUID.test(text)) {
    throw new CommandFormatError(`${field} is not an id`);
  }
  return text;
}

function integer(value: unknown, field: string, fallback: number | null): number {
  if (value === undefined || value === null) {
    if (fallback === null) {
      throw new CommandFormatError(`${field} is required for this command`);
    }
    return fallback;
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new CommandFormatError(`${field} must be a whole number`);
  }
  return value;
}

function word<T extends string>(value: unknown, field: string, allowed: readonly string[], fallback?: T): T {
  if (value === undefined || value === null) {
    if (fallback === undefined) {
      throw new CommandFormatError(`${field} is required for this command`);
    }
    return fallback;
  }
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new CommandFormatError(`${field} is not a known value`);
  }
  return value as T;
}

function optionalWord<T extends string>(value: unknown, field: string, allowed: readonly string[]): T | null {
  return value === undefined || value === null ? null : word<T>(value, field, allowed);
}

function dismissal(value: unknown): Dismissal | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRaw(value)) {
    throw new CommandFormatError('dismissal must be an object');
  }
  return {
    type: word(value.type, 'dismissal.type', VOCABULARY.dismissalTypes),
    batterOut: uuid(value.batterOut, 'dismissal.batterOut', false),
    end: optionalWord(value.end, 'dismissal.end', VOCABULARY.creaseEnds),
    fielderId: uuid(value.fielderId, 'dismissal.fielderId', false),
  };
}

function delivery(value: unknown): Delivery {
  if (!isRaw(value)) {
    throw new CommandFormatError('delivery is required for this command');
  }
  const kind = word<Delivery['kind']>(value.kind, 'delivery.kind', VOCABULARY.deliveryKinds, 'NORMAL');
  const runsType = optionalWord<NonNullable<Delivery['runsType']>>(value.runsType, 'delivery.runsType', VOCABULARY.runsTypes);
  if (value.boundary !== undefined && value.boundary !== null && typeof value.boundary !== 'boolean') {
    throw new CommandFormatError('delivery.boundary must be true or false');
  }
  return {
    kind,
    runs: integer(value.runs, 'delivery.runs', 0),
    // A wide carries no runs type; any other ball defaults to runs off the bat (the Java `Delivery` constructor).
    runsType: kind === 'WIDE' ? null : (runsType ?? 'BAT'),
    boundary: value.boundary === true,
    dismissal: dismissal(value.dismissal),
  };
}

/** The reason the way the server reads it: trimmed and upper-cased, then it has to be a known word. */
function reason<T extends string>(value: unknown, allowed: readonly string[]): T {
  if (value === undefined || value === null) {
    throw new CommandFormatError('reason is required for this command');
  }
  const text = String(value).trim().toUpperCase();
  if (!allowed.includes(text)) {
    throw new CommandFormatError(`Unknown reason: ${String(value)}`);
  }
  return text as T;
}

export function parseCommand(body: unknown): ScoreCommand {
  if (!isRaw(body)) {
    throw new CommandFormatError('A command is an object');
  }
  const type = word<ScoreCommand['type']>(body.type, 'type', VOCABULARY.commandTypes);
  switch (type) {
    case 'START_INNINGS':
      return { type, striker: uuid(body.striker, 'striker', true), nonStriker: uuid(body.nonStriker, 'nonStriker', true), bowler: uuid(body.bowler, 'bowler', true) };
    case 'DELIVER':
      return { type, delivery: delivery(body.delivery) };
    case 'SELECT_BATTER':
      return { type, batterId: uuid(body.batterId, 'batterId', true) };
    case 'SELECT_BOWLER':
    case 'CHANGE_BOWLER':
      return { type, bowlerId: uuid(body.bowlerId, 'bowlerId', true) };
    case 'RETIRE_BATTER':
      return { type, batterId: uuid(body.batterId, 'batterId', true), retirement: word<RetirementType>(body.retirement, 'retirement', VOCABULARY.retirementTypes) };
    case 'REVISE_TARGET':
      return { type, target: integer(body.target, 'target', null) };
    case 'END_INNINGS':
      return { type, reason: reason<EndReason>(body.reason, VOCABULARY.endReasons) };
    case 'END_MATCH':
      return { type, reason: reason<EndMatchReason>(body.reason, VOCABULARY.endMatchReasons) };
  }
}

/** The body the phone sends for a command: fields that are null are left out (as the Java vector writer does). */
export function toBody(command: ScoreCommand): Record<string, unknown> {
  switch (command.type) {
    case 'DELIVER': {
      const d = command.delivery;
      const out: Record<string, unknown> = { kind: d.kind, runs: d.runs };
      if (d.runsType !== null) {
        out.runsType = d.runsType;
      }
      out.boundary = d.boundary;
      if (d.dismissal !== null) {
        const x: Record<string, unknown> = { type: d.dismissal.type };
        if (d.dismissal.batterOut !== null) {
          x.batterOut = d.dismissal.batterOut;
        }
        if (d.dismissal.end !== null) {
          x.end = d.dismissal.end;
        }
        if (d.dismissal.fielderId !== null) {
          x.fielderId = d.dismissal.fielderId;
        }
        out.dismissal = x;
      }
      return { type: command.type, delivery: out };
    }
    default:
      return { ...command };
  }
}
