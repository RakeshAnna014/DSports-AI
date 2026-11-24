import axios from 'axios';
import { installAuth } from '@/api/client';

/** The refused-message log lives in the AI service (proxied at /ai-api) and shares the bearer token. The service enforces the ADMIN role. */
const client = installAuth(axios.create({ baseURL: '/ai-api/v1/topic', timeout: 30_000 }));

export interface RefusedMessage {
  /** ISO 8601 time. */
  time: string;
  topic: 'OFF_TOPIC' | 'BUSINESS';
  role: 'GUEST' | 'CUSTOMER' | 'ADMIN';
  /** Redacted and cut to 300 characters by the server. Never says who asked. */
  text: string;
}

export interface RefusalFilter { topic?: RefusedMessage['topic']; role?: RefusedMessage['role']; limit?: number }

export const topicRefusalsApi = {
  list: (filter: RefusalFilter) => client.get<{ items: RefusedMessage[] }>('/refusals', { params: { limit: 200, ...filter } }).then((r) => r.data.items),
  /** The same rows as a CSV file. */
  csv: (filter: RefusalFilter) => client.get<Blob>('/refusals', { params: { limit: 1000, ...filter, format: 'csv' }, responseType: 'blob' }).then((r) => r.data),
};
