export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
}

export interface LoginResponse {
  userId: string;
  email: string;
  roles: string[];
  accessToken: string;
  refreshToken: string;
}

export interface RegisterResponse {
  userId: string;
  email: string;
}

export interface RefreshResponse {
  accessToken: string;
  refreshToken: string;
}

export interface AuthUser {
  userId: string;
  email: string;
  roles: string[];
}
