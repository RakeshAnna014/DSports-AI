import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

configure({ asyncUtilTimeout: 5000 });   // the default 1 s is too tight when several MUI test files run in parallel on a busy machine
afterEach(() => cleanup());

/**
 * The app imports icons from the '@mui/icons-material' barrel, which loads ~10,000 modules (minutes under jsdom). Tests do not assert on
 * icon artwork, so every named icon becomes an empty component. This changes nothing in the production bundle.
 * `then` and symbols must stay undefined: a function there would make the module look like a thenable and `await import()` would hang.
 */
vi.mock('@mui/icons-material', () => {
  const Icon = () => null;
  return new Proxy({ __esModule: true } as Record<string | symbol, unknown>, {
    get: (target, name) => (name in target ? target[name] : typeof name === 'symbol' || name === 'then' || name === 'default' ? undefined : Icon),
    has: (_, name) => typeof name !== 'symbol' && name !== 'then',
  });
});
