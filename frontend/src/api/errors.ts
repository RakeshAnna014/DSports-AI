import { AxiosError } from 'axios';

/** Error body returned by every backend endpoint; the HTTP status always matches `status`. */
export interface ApiError {
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  correlationId: string | null;
  timestamp: string;
  validationErrors: { field: string; message: string }[] | null;
}

export const getApiError = (err: unknown): ApiError | null => {
  if (err instanceof AxiosError && err.response?.data && typeof err.response.data === 'object') {
    const data = err.response.data as Partial<ApiError>;
    if (typeof data.code === 'string' && typeof data.message === 'string') {
      return data as ApiError;
    }
  }
  return null;
};

export const getApiErrorMessage = (err: unknown, fallback: string): string => {
  const apiError = getApiError(err);
  if (apiError) {
    const firstFieldError = apiError.validationErrors?.[0];
    return firstFieldError ? `${firstFieldError.field}: ${firstFieldError.message}` : apiError.message;
  }
  return fallback;
};
