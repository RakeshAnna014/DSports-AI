import axios, { type AxiosInstance } from 'axios';

/**
 * Adds the stored access token to every request and, on a 401, refreshes it once and retries.
 * Shared by the commerce API client and the AI assistant client, so there is exactly one authentication mechanism.
 */
export const installAuth = (instance: AxiosInstance): AxiosInstance => {
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  instance.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config;
      const isAuthEndpoint = typeof originalRequest?.url === 'string' && originalRequest.url.startsWith('/auth/');
      if (error.response?.status === 401 && !isAuthEndpoint && !originalRequest._retry) {
        originalRequest._retry = true;
        const refreshToken = localStorage.getItem('refreshToken');
        if (refreshToken) {
          try {
            const { data } = await axios.post<{ accessToken: string; refreshToken: string }>(
              '/api/auth/refresh',
              { refreshToken },
            );
            localStorage.setItem('accessToken', data.accessToken);
            localStorage.setItem('refreshToken', data.refreshToken);
            originalRequest.headers.Authorization = `Bearer ${data.accessToken}`;
            return instance(originalRequest);
          } catch {
            localStorage.removeItem('accessToken');
            localStorage.removeItem('refreshToken');
            window.location.href = '/login';
          }
        }
      }
      return Promise.reject(error);
    },
  );
  return instance;
};

const apiClient = installAuth(
  axios.create({
    baseURL: '/api',
    headers: { 'Content-Type': 'application/json' },
  }),
);

export default apiClient;
