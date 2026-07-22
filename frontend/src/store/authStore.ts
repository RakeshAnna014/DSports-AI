import { create } from 'zustand';
import type { AuthUser, LoginRequest, RegisterRequest } from '@/types/auth';
import { authApi } from '@/api/auth';

interface AuthState {
  user: AuthUser | null;
  isAuthenticated: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  hydrate: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,

  login: async (data) => {
    const result = await authApi.login(data);
    localStorage.setItem('accessToken', result.accessToken);
    localStorage.setItem('refreshToken', result.refreshToken);
    set({
      user: { userId: result.userId, email: result.email, roles: result.roles },
      isAuthenticated: true,
    });
  },

  register: async (data) => {
    await authApi.register(data);
  },

  logout: async () => {
    const refreshToken = localStorage.getItem('refreshToken');
    if (refreshToken) {
      try {
        await authApi.logout(refreshToken);
      } catch {
        // ignore
      }
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    set({ user: null, isAuthenticated: false });
  },

  hydrate: () => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        set({
          user: {
            userId: payload.sub,
            email: payload.email ?? '',
            roles: payload.roles ?? [],
          },
          isAuthenticated: true,
        });
      } catch {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
      }
    }
  },
}));
