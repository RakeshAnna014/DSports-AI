import '@testing-library/jest-dom/vitest';
import { __unsafe_useEmotionCache as useEmotionCache } from '@emotion/react';
import { cleanup, configure, renderHook } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

configure({ asyncUtilTimeout: 5000 });   // the default 1 s is too tight when several MUI test files run in parallel on a busy machine

/**
 * Emotion (the style engine under Material UI) adds every CSS rule it needs to the document and keeps it there. A test file mounts the same screens again and
 * again, so by its twentieth test the document holds over a thousand rules, and jsdom's getComputedStyle (which a multi-line MUI text field calls on every
 * keystroke) reads all of them each time: typing 24 characters took about 11 s late in PlanningApp.test.tsx and 1.5 s alone. Emptying the sheet and the cache's
 * record of what it has inserted after each test means every test starts with only the rules it renders, and nothing else changes.
 */
const resetEmotionStyles = () => {
  const { result, unmount } = renderHook(() => useEmotionCache());
  const cache = result.current;
  unmount();
  if (cache) {
    cache.sheet.flush();
    cache.inserted = {};
  }
};

afterEach(() => {
  cleanup();
  resetEmotionStyles();
});

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
