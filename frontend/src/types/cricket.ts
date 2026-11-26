/** Mirrors the cricket API (backend/cricket): players, teams and guest-profile claims. */
export type PlayingRole = 'BATTER' | 'BOWLER' | 'ALL_ROUNDER' | 'WICKET_KEEPER';
export type BattingHand = 'RIGHT' | 'LEFT';

export const ROLE_LABEL: Record<PlayingRole, string> = { BATTER: 'Batter', BOWLER: 'Bowler', ALL_ROUNDER: 'All-rounder', WICKET_KEEPER: 'Wicket-keeper' };

export interface Player {
  id: string;
  name: string;
  /** Only the person who created the profile ever sees the phone number. */
  phone: string | null;
  role: PlayingRole | null;
  battingHand: BattingHand | null;
  bowlingStyle: string | null;
  photoUrl: string | null;
  /** Linked to a registered account. */
  linked: boolean;
  /** Linked to the signed-in user's own account. */
  mine: boolean;
  /** Has a public statistics page: linked to an account and not hidden. */
  publicProfile: boolean;
  version: number;
}

export interface PlayerInput {
  name: string;
  phone?: string;
  role?: PlayingRole;
  battingHand?: BattingHand;
  bowlingStyle?: string;
}

export interface TeamMember {
  playerId: string;
  name: string;
  role: PlayingRole | null;
  battingHand: BattingHand | null;
  captain: boolean;
  linked: boolean;
  phone: string | null;
}

export interface Team {
  id: string;
  name: string;
  city: string | null;
  status: 'ACTIVE' | 'ARCHIVED';
  version: number;
  canEdit: boolean;
  members: TeamMember[];
}

export interface Claimable {
  playerId: string;
  displayName: string;
}

export interface Claim {
  id: string;
  playerId: string;
  playerName: string | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  /** Filled only for the person who must decide the claim. */
  claimantName: string | null;
  claimantEmail: string | null;
  createdAt: string;
  decidedAt: string | null;
  /** What approval makes public and how to hide it; null once rejected. */
  notice: string | null;
}

export type BallType = 'LEATHER' | 'TENNIS';
export type TossDecision = 'BAT' | 'BOWL';
export type MatchStatus = 'SCHEDULED' | 'TOSS_DONE' | 'IN_PROGRESS' | 'INNINGS_BREAK' | 'COMPLETED' | 'ABANDONED' | 'NO_RESULT';

export interface MatchRules {
  ballsPerOver: number;
  playersPerSide: number;
  maxOversPerBowler: number | null;
  widePenalty: number;
  noBallPenalty: number;
  freeHitEnabled: boolean;
  lastManStands: boolean;
}

export const STANDARD_RULES: MatchRules = { ballsPerOver: 6, playersPerSide: 11, maxOversPerBowler: null, widePenalty: 1, noBallPenalty: 1, freeHitEnabled: true, lastManStands: false };

export interface Selected {
  playerId: string;
  name: string | null;
  battingOrder: number;
}

export interface MatchSide {
  teamId: string;
  teamName: string;
  playingXi: Selected[];
  /** Every member of the team, to pick the XI from; only people who can score get it. */
  squad: Selected[];
}

export interface Match {
  id: string;
  status: MatchStatus;
  version: number;
  teamA: MatchSide;
  teamB: MatchSide;
  overs: number;
  ballType: BallType;
  rules: MatchRules;
  scheduledAt: string | null;
  city: string | null;
  venue: { bookingId: string; name: string | null; city: string | null } | null;
  umpires: string | null;
  toss: { winnerTeamId: string; decision: TossDecision } | null;
  battingFirstTeamId: string | null;
  readyToStart: boolean;
  /** The creator: settings and scorers. */
  canManage: boolean;
  /** The creator or an assigned scorer: toss and playing XIs. */
  canScore: boolean;
  scorers: { userId: string; name: string | null; email: string | null }[];
}

export interface MatchInput {
  teamAId: string;
  teamBId: string;
  overs: number;
  ballType: BallType;
  rules?: Partial<MatchRules>;
  scheduledAt?: string;
  city?: string;
  umpires?: string;
  bookingId?: string;
}
