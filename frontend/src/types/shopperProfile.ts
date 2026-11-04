/** Mirrors backend/identity ShopperProfileDtos. Every preference is optional. */
export type AgeBand = 'CHILD_UNDER_8' | 'CHILD_8_12' | 'TEEN_13_17' | 'ADULT';
export type UsageLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED' | 'PROFESSIONAL';

export const AGE_BANDS: { value: AgeBand; label: string }[] = [
  { value: 'CHILD_UNDER_8', label: 'Under 8' },
  { value: 'CHILD_8_12', label: '8 to 12' },
  { value: 'TEEN_13_17', label: '13 to 17' },
  { value: 'ADULT', label: 'Adult' },
];

export const USAGE_LEVELS: { value: UsageLevel; label: string }[] = [
  { value: 'BEGINNER', label: 'Beginner' },
  { value: 'INTERMEDIATE', label: 'Intermediate' },
  { value: 'ADVANCED', label: 'Advanced' },
  { value: 'PROFESSIONAL', label: 'Professional' },
];

/** The size / fit fields the backend accepts. */
export const SIZE_FIELDS: { key: string; label: string }[] = [
  { key: 'shoe', label: 'Shoe size' },
  { key: 'clothing', label: 'Clothing size' },
  { key: 'glove', label: 'Glove size' },
  { key: 'fit', label: 'Preferred fit' },
];

export interface ProfilePreferences {
  sports: string[];
  favoriteBrands: string[];
  favoriteCategories: string[];
  favoriteTeams: string[];
  usageLevel: UsageLevel | null;
  priceMin: number | null;
  priceMax: number | null;
  sizes: Record<string, string>;
  attributes: Record<string, string>;
  empty: boolean;
}

export interface ShopperProfile {
  id: string;
  kind: 'OWNER' | 'FAMILY';
  displayName: string;
  relationship: string | null;
  ageBand: AgeBand | null;
  personalizationEnabled: boolean;
  preferences: ProfilePreferences;
  createdAt: string;
  updatedAt: string;
}

export interface ProfilesOverview {
  activeProfileId: string;
  profiles: ShopperProfile[];
  maxFamilyProfiles: number;
}

export interface PreferencesPayload {
  sports?: string[];
  favoriteBrands?: string[];
  favoriteCategories?: string[];
  favoriteTeams?: string[];
  usageLevel?: UsageLevel | '';
  priceMin?: number | null;
  priceMax?: number | null;
  sizes?: Record<string, string>;
  personalizationEnabled?: boolean;
}

export interface FamilyPayload {
  name: string;
  relationship?: string;
  ageBand: AgeBand;
  preferences?: PreferencesPayload;
}
