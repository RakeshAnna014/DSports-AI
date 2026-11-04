import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { shopperProfilesApi } from '@/api/shopperProfiles';
import { useAuthStore } from '@/store/authStore';

export const PROFILES_KEY = ['shopper-profiles'] as const;

/** The account's profiles and which one is active. Anything that shows recommendations must refresh when this changes. */
export const useShopperProfiles = () => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  return useQuery({ queryKey: PROFILES_KEY, queryFn: shopperProfilesApi.overview, enabled: isAuthenticated, retry: false, staleTime: 30_000 });
};

/** Switches the active profile, then drops cached recommendations so the next render reflects the new profile. */
export const useSelectProfile = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => shopperProfilesApi.select(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: PROFILES_KEY });
      void qc.invalidateQueries({ queryKey: ['ai-recommendations'] });
      void qc.invalidateQueries({ queryKey: ['reco'] });
    },
  });
};
