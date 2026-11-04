import apiClient from '@/api/client';
import type { FamilyPayload, PreferencesPayload, ProfilesOverview, ShopperProfile } from '@/types/shopperProfile';

const P = '/customers/me';

/** Preferences and family profiles live in the Java backend (the authoritative profile domain). The account is always the token's subject. */
export const shopperProfilesApi = {
  overview: () => apiClient.get<ProfilesOverview>(`${P}/profiles`).then((r) => r.data),
  active: () => apiClient.get<ShopperProfile>(`${P}/profiles/active`).then((r) => r.data),
  select: (profileId: string) => apiClient.put<ShopperProfile>(`${P}/profiles/active`, { profileId }).then((r) => r.data),
  savePreferences: (id: string, payload: PreferencesPayload) => apiClient.put<ShopperProfile>(`${P}/profiles/${id}/preferences`, payload).then((r) => r.data),
  resetPreferences: (id: string) => apiClient.delete<ShopperProfile>(`${P}/profiles/${id}/preferences`).then((r) => r.data),
  createFamily: (payload: FamilyPayload) => apiClient.post<ShopperProfile>(`${P}/family-profiles`, payload).then((r) => r.data),
  updateFamily: (id: string, payload: FamilyPayload) => apiClient.put<ShopperProfile>(`${P}/family-profiles/${id}`, payload).then((r) => r.data),
  deleteFamily: (id: string) => apiClient.delete(`${P}/family-profiles/${id}`).then(() => undefined),
};
