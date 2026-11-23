import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { newLog, parseCommand, stepCommand, stepUndo, type MatchSetup, type ScoringLog } from '../engine';
import type { ScoringView } from '../../../types/cricketScoring';
import { buildLocalView, describe as describeCommand, economy, nameBookFrom, type ViewContext } from './localView';

/**
 * The scorer's view built on the phone must be the view the server builds. `testdata/cricket-engine/server/scoring-views-golden-match.json` holds the real
 * server view after each of the 36 commands of the golden match (written by the Java build); the phone folds the same commands with its own engine and builds
 * its own view from the names it would have kept, and every field the pad or the scoreboard reads must be equal. (The recent events are left out: their times and
 * authors are the server's, and the phone shows its own.)
 */
const ROOT = resolve(process.cwd(), '..', 'testdata', 'cricket-engine');
const views = (JSON.parse(readFileSync(join(ROOT, 'server', 'scoring-views-golden-match.json'), 'utf8')) as { views: ScoringView[] }).views;
const golden = JSON.parse(readFileSync(join(ROOT, 'golden', 'golden-match.json'), 'utf8')) as { setup: MatchSetup; steps: { op: string; command: unknown }[] };

const last = <T,>(items: T[]): T | undefined => items[items.length - 1];

const without = (v: ScoringView): Omit<ScoringView, 'recentEvents' | 'matchId'> => {
  const { recentEvents: _recent, matchId: _id, ...rest } = v;
  return rest;
};

describe('the local view equals the server view', () => {
  const names = nameBookFrom(views[views.length - 1]);
  const ctxFor = (i: number): ViewContext => ({ matchId: views[i].matchId, names, editableUntil: views[i].editableUntil, locked: views[i].locked, eventTimes: new Map(), ownLabel: 'You' });

  it('has the server views to compare with', () => {
    expect(views).toHaveLength(36);
    expect(golden.steps).toHaveLength(36);
  });

  it('is the same after every one of the 36 commands of the golden match (innings tables, choices, what is needed, undo, result, fingerprint)', () => {
    let log: ScoringLog = newLog(golden.setup);
    golden.steps.forEach((step, i) => {
      const next = stepCommand(log, parseCommand(step.command));
      expect(next.result.accepted).toBe(true);
      log = next.log;
      const local = buildLocalView(log, ctxFor(i));
      expect(without(local), `after command ${i + 1}`).toEqual(without(views[i]));
    });
  });

  it('shows the same recent events text for the commands it knows (the words the scorer reads)', () => {
    let log: ScoringLog = newLog(golden.setup);
    for (const step of golden.steps) {
      log = stepCommand(log, parseCommand(step.command)).log;
    }
    const local = buildLocalView(log, ctxFor(35));
    expect(local.recentEvents).toHaveLength(12);
    expect(last(local.recentEvents)).toMatchObject({ seq: 36, kind: 'DELIVER', text: '3 runs', at: '' });
    expect(local.recentEvents.map((e) => e.seq)).toEqual(Array.from({ length: 12 }, (_, k) => 25 + k));
  });

  it('shows what an undo would do before it is done, and the view after it equals the view before the undone ball', () => {
    let log: ScoringLog = newLog(golden.setup);
    golden.steps.slice(0, 12).forEach((step, i) => {
      log = stepCommand(log, parseCommand(step.command)).log;
      if (i === 10) {
        expect(buildLocalView(log, ctxFor(i)).canUndo).toEqual(views[i].canUndo);
      }
    });
    const before = buildLocalView(stepUndo(log, 'COMMAND').log, ctxFor(10));
    expect(without(before).innings).toEqual(without(views[10]).innings);
    const undone = last(buildLocalView(log, ctxFor(11)).recentEvents);
    expect(undone).toMatchObject({ kind: 'SELECT_BOWLER' });
  });

  it('writes the undo of a ball in words, with the names, as the server does', () => {
    let log: ScoringLog = newLog(golden.setup);
    for (const step of golden.steps.slice(0, 3)) {
      log = stepCommand(log, parseCommand(step.command)).log;
    }
    log = stepUndo(log, 'BALL').log;
    const local = buildLocalView(log, ctxFor(2));
    expect(last(local.recentEvents)?.text).toBe('Undid: 1 run');
    expect(local.canUndo.describe).toBe('Undo: wide');
    expect(describeCommand(parseCommand({ type: 'SELECT_BATTER', batterId: golden.setup.xiA[2] }), names.player)).toBe('Lion 3 comes in');
  });
});

describe('economy (runs per over, two places, half up, as BigDecimal)', () => {
  it('matches the Java rounding', () => {
    expect(economy(0, 0, 6)).toBe(0);
    expect(economy(10, 6, 6)).toBe(10);
    expect(economy(7, 4, 6)).toBe(10.5);
    expect(economy(1, 3, 6)).toBe(2);
    expect(economy(1, 7, 6)).toBe(0.86);
    expect(economy(5, 8, 6)).toBe(3.75);
    expect(economy(1, 16, 6)).toBe(0.38);
    expect(economy(100, 7, 6)).toBe(85.71);
    expect(economy(2, 16, 6)).toBe(0.75);
    expect(economy(1, 40, 6)).toBe(0.15);
  });
});
