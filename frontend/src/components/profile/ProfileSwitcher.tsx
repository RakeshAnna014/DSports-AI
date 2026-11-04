import { Chip, Stack, Typography } from '@mui/material';
import { FamilyRestroomOutlined, PersonOutline } from '@mui/icons-material';
import { useSelectProfile, useShopperProfiles } from '@/features/profile/useShopperProfiles';

/**
 * Chooses whose preferences shape recommendations. The owner and each family member are visibly different (icon and label), and the
 * choice is stored by the backend so it is the same on every page and device. Renders nothing for a guest or when there is only one profile.
 */
const ProfileSwitcher = ({ label = 'Shopping for' }: { label?: string }) => {
  const { data } = useShopperProfiles();
  const select = useSelectProfile();
  if (!data || data.profiles.length < 2) return null;
  return (
    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap data-testid="profile-switcher" role="group" aria-label={label}>
      <Typography variant="caption" color="text.secondary" fontWeight={700}>{label}</Typography>
      {data.profiles.map((p) => {
        const active = p.id === data.activeProfileId;
        return (
          <Chip key={p.id} size="small" clickable color={active ? 'primary' : 'default'} variant={active ? 'filled' : 'outlined'} disabled={select.isPending}
            icon={p.kind === 'OWNER' ? <PersonOutline /> : <FamilyRestroomOutlined />}
            label={p.kind === 'OWNER' ? 'Me' : p.displayName} aria-pressed={active} data-testid={`switch-${p.kind === 'OWNER' ? 'owner' : 'family'}`}
            onClick={() => { if (!active) select.mutate(p.id); }} />
        );
      })}
    </Stack>
  );
};

export default ProfileSwitcher;
