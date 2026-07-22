export interface CustomerProfile {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber: string | null;
  profileImageUrl: string | null;
  dateOfBirth: string | null;
  roles: string[];
}
