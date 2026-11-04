import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

/**
 * Flat config (ESLint 9). Recommended TypeScript rules plus the React hooks rules. Genuine problems are errors; nothing is silenced with a blanket
 * disable. Test files may use non-null assertions on mocked values.
 */
export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage', 'scripts/**', 'vite.config.ts', 'vitest.config.ts'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2022, globals: { ...globals.browser, ...globals.es2021 } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  { files: ['src/**/*.test.{ts,tsx}', 'src/test/**'], rules: { '@typescript-eslint/no-non-null-assertion': 'off', '@typescript-eslint/no-explicit-any': 'off' } },
);
