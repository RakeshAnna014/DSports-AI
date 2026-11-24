import apiClient from './client';
import type { Claim, Claimable, Match, MatchInput, Player, PlayerInput, Team, TossDecision } from '@/types/cricket';

/** Every call needs a sign-in; the server decides who may see or change what (a stranger gets 404, a member who may not edit gets 403). */
export const cricketApi = {
  players: {
    mine: () => apiClient.get<Player[]>('/v1/players').then((r) => r.data),
    createGuest: (input: PlayerInput) => apiClient.post<Player>('/v1/players', input).then((r) => r.data),
    setPublicProfile: (visible: boolean) => apiClient.put<Player>('/v1/players/me/public-profile', { visible }).then((r) => r.data),
    createOwn: (input: PlayerInput) => apiClient.post<Player>('/v1/players/me', input).then((r) => r.data),
    update: (id: string, player: PlayerInput, expectedVersion: number) => apiClient.put<Player>(`/v1/players/${id}`, { player, expectedVersion }).then((r) => r.data),
    claimable: (phone: string) => apiClient.get<Claimable[]>('/v1/players/claimable', { params: { phone } }).then((r) => r.data),
    claim: (playerId: string) => apiClient.post<Claim>(`/v1/players/${playerId}/claims`).then((r) => r.data),
    incomingClaims: () => apiClient.get<Claim[]>('/v1/players/claims/incoming').then((r) => r.data),
    myClaims: () => apiClient.get<Claim[]>('/v1/players/claims/mine').then((r) => r.data),
    approveClaim: (claimId: string) => apiClient.post<Claim>(`/v1/players/claims/${claimId}/approve`).then((r) => r.data),
    rejectClaim: (claimId: string) => apiClient.post<Claim>(`/v1/players/claims/${claimId}/reject`).then((r) => r.data),
  },
  teams: {
    mine: () => apiClient.get<Team[]>('/v1/teams').then((r) => r.data),
    get: (id: string) => apiClient.get<Team>(`/v1/teams/${id}`).then((r) => r.data),
    create: (name: string, city?: string) => apiClient.post<Team>('/v1/teams', { name, city: city || undefined }).then((r) => r.data),
    update: (id: string, name: string, city: string | undefined, expectedVersion: number) =>
      apiClient.put<Team>(`/v1/teams/${id}`, { name, city: city || undefined, expectedVersion }).then((r) => r.data),
    archive: (id: string) => apiClient.post<Team>(`/v1/teams/${id}/archive`).then((r) => r.data),
    addMember: (id: string, playerId: string) => apiClient.post<Team>(`/v1/teams/${id}/members`, { playerId }).then((r) => r.data),
    removeMember: (id: string, playerId: string) => apiClient.delete<Team>(`/v1/teams/${id}/members/${playerId}`).then((r) => r.data),
    setCaptain: (id: string, playerId: string) => apiClient.put<Team>(`/v1/teams/${id}/captain`, { playerId }).then((r) => r.data),
  },
  matches: {
    mine: () => apiClient.get<Match[]>('/v1/matches').then((r) => r.data),
    get: (id: string) => apiClient.get<Match>(`/v1/matches/${id}`).then((r) => r.data),
    create: (input: MatchInput) => apiClient.post<Match>('/v1/matches', input).then((r) => r.data),
    update: (id: string, settings: Omit<MatchInput, 'teamAId' | 'teamBId' | 'bookingId'>, expectedVersion: number) => apiClient.put<Match>(`/v1/matches/${id}`, { ...settings, expectedVersion }).then((r) => r.data),
    toss: (id: string, winnerTeamId: string, decision: TossDecision) => apiClient.post<Match>(`/v1/matches/${id}/toss`, { winnerTeamId, decision }).then((r) => r.data),
    setPlayingXi: (id: string, teamId: string, playerIds: string[]) => apiClient.put<Match>(`/v1/matches/${id}/playing-xi`, { teamId, playerIds }).then((r) => r.data),
    addScorer: (id: string, email: string) => apiClient.post<Match>(`/v1/matches/${id}/scorers`, { email }).then((r) => r.data),
    removeScorer: (id: string, userId: string) => apiClient.delete<Match>(`/v1/matches/${id}/scorers/${userId}`).then((r) => r.data),
  },
};
