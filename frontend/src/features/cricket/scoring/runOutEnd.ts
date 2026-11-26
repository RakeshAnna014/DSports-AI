import type { CreaseEnd } from '@/types/cricketScoring';

export type RunOutBatter = 'STRIKER' | 'NON_STRIKER';

/**
 * The end at which a run out most likely happened, from the runs completed. After r completed runs the batters have crossed r times, and the batter who is
 * out was running to the end he had not yet reached:
 *
 *   batter out    r even                  r odd
 *   striker       bowler's end            striker's end
 *   non-striker   striker's end           bowler's end
 *
 * It is only a default. The scorer changes it for a batter run out backing up, a run out where the batters did not cross, or any case the rule does not fit.
 */
export const defaultRunOutEnd = (batter: RunOutBatter, runsCompleted: number): CreaseEnd => {
  const odd = runsCompleted % 2 === 1;
  return (batter === 'STRIKER') !== odd ? 'BOWLERS_END' : 'BATTING_END';
};

/** Who faces next after the run out: the new batter if he takes the striker's end, otherwise the batter who stays in. At the end of an over the ends swap. */
export const nextToFace = (end: CreaseEnd, lastBallOfOver: boolean): 'NEW_BATTER' | 'PARTNER' =>
  (end === 'BATTING_END') !== lastBallOfOver ? 'NEW_BATTER' : 'PARTNER';

/** One line shown under the end control so the scorer sees what the choice means. It is a preview; the server's answer is the truth. */
export const runOutPreview = (out: string, partner: string | null, end: CreaseEnd, lastBallOfOver: boolean): string => {
  const faces = nextToFace(end, lastBallOfOver);
  const next = faces === 'NEW_BATTER' || !partner ? 'The new batter will face.' : `${partner} will face.`;
  return `${out} is out. ${next}`;
};
