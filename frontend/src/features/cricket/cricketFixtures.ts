import type { Claim, Match, Player, Team } from '@/types/cricket';

export const PLAYER: Player = { id: 'p-1', name: 'Arjun Kumar', phone: '+919876543210', role: 'BOWLER', battingHand: 'RIGHT', bowlingStyle: null, photoUrl: null, linked: false, mine: false, version: 1 };
export const PLAYER_2: Player = { ...PLAYER, id: 'p-2', name: 'Bala Raj', phone: null, role: 'BATTER' };
export const OWN: Player = { ...PLAYER, id: 'p-own', name: 'Me Myself', phone: null, linked: true, mine: true };

export const TEAM: Team = {
  id: 't-1', name: 'Green Lions', city: 'Chennai', status: 'ACTIVE', version: 3, canEdit: true,
  members: [
    { playerId: 'p-1', name: 'Arjun Kumar', role: 'BOWLER', battingHand: 'RIGHT', captain: true, linked: false, phone: '+919876543210' },
    { playerId: 'p-3', name: 'Chetan', role: null, battingHand: null, captain: false, linked: true, phone: null },
  ],
};

export const CLAIM: Claim = { id: 'c-1', playerId: 'p-1', playerName: 'Arjun Kumar', status: 'PENDING', claimantName: 'Arjun Kumar', claimantEmail: 'arjun@example.com', createdAt: '2026-11-17T10:00:00Z', decidedAt: null };

const squad = (prefix: string, n: number) => Array.from({ length: n }, (_, i) => ({ playerId: `${prefix}-${i + 1}`, name: `${prefix.toUpperCase()} player ${i + 1}`, battingOrder: 0 }));

export const MATCH: Match = {
  id: 'm-1', status: 'SCHEDULED', version: 1,
  teamA: { teamId: 'ta', teamName: 'Lions', playingXi: [], squad: squad('a', 4) },
  teamB: { teamId: 'tb', teamName: 'Tigers', playingXi: [], squad: squad('b', 4) },
  overs: 20, ballType: 'TENNIS', rules: { ballsPerOver: 6, playersPerSide: 3, maxOversPerBowler: null, widePenalty: 1, noBallPenalty: 1, freeHitEnabled: true, lastManStands: false },
  scheduledAt: null, city: 'Chennai', venue: null, umpires: null, toss: null, battingFirstTeamId: null, readyToStart: false, canManage: true, canScore: true, scorers: [],
};
