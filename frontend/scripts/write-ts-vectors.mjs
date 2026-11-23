// Regenerates testdata/cricket-engine/from-ts with the TypeScript engine. Review the diff before committing it: a changed file means the TypeScript engine's
// answers changed, and the Java build (TsVectorsReplayTest) will say whether the Java engine agrees. No new dependency: the test file does the writing when
// VECTORS_WRITE=1 is set, and this script only sets it in a way that works on Windows, macOS and Linux.
import { spawnSync } from 'node:child_process';

const result = spawnSync('pnpm', ['exec', 'vitest', 'run', 'src/features/cricket/engine/tsVectors.test.ts'], {
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, VECTORS_WRITE: '1' },
});
process.exit(result.status ?? 1);
