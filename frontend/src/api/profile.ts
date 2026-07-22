import apiClient from './client';
import type { CustomerProfile } from '@/types/profile';

export interface UpdateProfileData {
  firstName: string;
  lastName: string;
  phoneNumber?: string;
  profileImageUrl?: string;
  dateOfBirth?: string;
}

export const profileApi = {
  getProfile: () =>
    apiClient.get<CustomerProfile>('/customers/me').then((r) => r.data),

  updateProfile: (data: UpdateProfileData) =>
    apiClient.put<CustomerProfile>('/customers/me', data).then((r) => r.data),
};
