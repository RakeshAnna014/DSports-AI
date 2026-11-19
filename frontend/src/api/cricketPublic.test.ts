import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it, vi } from 'vitest';

const seen = vi.hoisted(() => [] as { url: string; params: Record<string, unknown> | undefined; method: string }[]);

// The module makes its own axios client; give every client created here an adapter that records the request and answers with an empty page.
vi.mock('axios', async (original) => {
  const real = await original<typeof import('axios')>();
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    seen.push({ url: `${config.baseURL ?? ''}${config.url ?? ''}`, params: config.params, method: String(config.method) });
    return { data: { items: [], page: 0, size: 20, hasNext: false }, status: 200, statusText: 'OK', headers: {}, config };
  };
  return { ...real, default: { ...real.default, create: (config: object) => real.default.create({ ...config, adapter }) } };
});

import { cricketPublicApi } from './cricketPublic';

describe('cricketPublicApi.live', () => {
  it('asks the public live path, with no status parameter, and leaves "my matches" alone', async () => {
    seen.length = 0;
    await cricketPublicApi.live({ city: 'Chennai', page: 1, size: 5 });

    expect(seen).toHaveLength(1);
    expect(seen[0].url).toBe('/api/v1/public/matches/live');
    expect(seen[0].method).toBe('get');
    expect(seen[0].params).toEqual({ city: 'Chennai', page: 1, size: 5 });
    expect(seen[0].params).not.toHaveProperty('status');
  });

  it('leaves a blank city out', async () => {
    seen.length = 0;
    await cricketPublicApi.live({ city: '', page: 0, size: 20 });
    expect(seen[0].params?.city).toBeUndefined();
  });
});
