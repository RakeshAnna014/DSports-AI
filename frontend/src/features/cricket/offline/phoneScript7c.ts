import { lion, tiger } from '../engine/tsVectors';
import type { ScoreCommand } from '../engine';

/**
 * The balls of the real-phone script 7c (docs/SETUP-AND-DEMO.md), as data. The doc quotes the totals this script gives; `phoneScript7c.test.ts` plays it through the
 * engine and fails if the doc and the engine disagree, so the person holding the phone is never told a number the software does not produce.
 * Players of the 7b match: Lions Arun, Bala, Chetan, Dev (lion 1 to 4); Tigers Tarun, Uday, Vik, Wasim (tiger 1 to 4). Two overs, four a side, tennis ball.
 */
export type Phase = 'online' | 'airplane';

export interface ScriptStep {
  phase: Phase;
  /** What to press, in the words of the screen. */
  press: string;
  /** The command it makes, or 'UNDO' for the undo button. */
  command: ScoreCommand | 'UNDO';
}

const ball = (runs: number, boundary = false): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs, runsType: 'BAT', boundary, dismissal: null } });
const bowled = (): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0, runsType: 'BAT', boundary: false, dismissal: { type: 'BOWLED', batterOut: null, end: null, fielderId: null } } });
const wide = (): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'WIDE', runs: 0, runsType: null, boundary: false, dismissal: null } });

export const NAMES: Record<string, string> = {};
['Arun', 'Bala', 'Chetan', 'Dev'].forEach((n, i) => (NAMES[lion(i + 1)] = n));
['Tarun', 'Uday', 'Vik', 'Wasim'].forEach((n, i) => (NAMES[tiger(i + 1)] = n));

export const SCRIPT_7C: ScriptStep[] = [
  { phase: 'online', press: 'Start innings (Arun and Bala open, Tarun bowls)', command: { type: 'START_INNINGS', striker: lion(1), nonStriker: lion(2), bowler: tiger(1) } },
  { phase: 'online', press: '1', command: ball(1) },
  { phase: 'online', press: '4', command: ball(4, true) },
  { phase: 'online', press: '•', command: ball(0) },
  { phase: 'online', press: 'W, Bowled, Confirm', command: bowled() },
  { phase: 'online', press: 'Who bats next? Chetan', command: { type: 'SELECT_BATTER', batterId: lion(3) } },
  // Airplane mode on from here.
  { phase: 'airplane', press: '3 (by mistake)', command: ball(3) },
  { phase: 'airplane', press: 'Undo: 3 runs', command: 'UNDO' },
  { phase: 'airplane', press: '2', command: ball(2) },
  { phase: 'airplane', press: '1 (the over is complete)', command: ball(1) },
  { phase: 'airplane', press: 'Who bowls the next over? Uday', command: { type: 'SELECT_BOWLER', bowlerId: tiger(2) } },
  { phase: 'airplane', press: 'WD, Wd', command: wide() },
  { phase: 'airplane', press: '1', command: ball(1) },
  { phase: 'airplane', press: 'W, Bowled, Confirm', command: bowled() },
  { phase: 'airplane', press: 'Who bats next? Dev', command: { type: 'SELECT_BATTER', batterId: lion(4) } },
  { phase: 'airplane', press: '6', command: ball(6, true) },
  { phase: 'airplane', press: '•', command: ball(0) },
  { phase: 'airplane', press: '2', command: ball(2) },
  { phase: 'airplane', press: '1 (the innings is over)', command: ball(1) },
];
