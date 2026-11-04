# DSports frontend

React 19 + TypeScript + Vite + MUI 6 + React Router 7 + TanStack Query + Zustand + Axios. Serves the storefront and the staff workspaces on port 3000 and proxies `/api` to the Java backend (8080) and `/ai-api` to the AI service (8090).

```bash
pnpm install                  # the lockfile is pnpm-lock.yaml: never use npm install
pnpm exec vite                # dev server
pnpm exec tsc -b              # type check
pnpm lint                     # ESLint, zero warnings allowed
pnpm exec vitest run          # unit and component tests
pnpm build                    # tsc -b && vite build
pnpm validate:catalog         # checks the demo-catalogue spec against the image files (:live also checks the running backend)
```

Guide (structure, request lifecycle, security notes, limitations): [docs/services/frontend.md](../docs/services/frontend.md). Routes are declared in `src/routes/index.tsx`. Authorization is enforced by the back ends; the UI has no client-side role guard.
