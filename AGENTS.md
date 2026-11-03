# ostrom-app contributor instructions

This is the mobile app, React Native (Expo) + TypeScript (strictest settings). AGPL-3.0, `ee/` under the commercial license.

**The conventions are in [ostrom-core `docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md) (local clone: `../ostrom-core/docs/CONVENTIONS.md`).** They follow Nathan Fallet's practices (teamcode, asonar,
pkg) and bind every contributor, human or agent. This file only summarizes the most important rules; when in
doubt, the conventions file wins, and it can be updated: re-read it before each pull request.

All repository content (code, comments, docs, commits, pull requests) is in English.

## Specific to this repository

API types and Zod schemas come from `@ostrom-hq/core` (generated from ostrom-core by Zodable): never hand-write an API entity type. Maestro end-to-end tests run on an Android emulator in CI. Every org-scoped screen has an end-to-end test that another organization's data never shows (CONVENTIONS.md section 16).

## The 15 rules that matter most

1. Read CONVENTIONS.md before each pull request (section 13 for TypeScript); it wins over habits from other projects.
2. `tsconfig` extends the strictest base (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `verbatimModuleSyntax`…). TypeScript 6.0.x until typescript-eslint supports 7.
3. ESLint flat config with typescript-eslint `strictTypeChecked` + `stylisticTypeChecked` and `noInlineConfig: true`: no `eslint-disable`, no `@ts-ignore`/`@ts-expect-error`.
4. No `any`, no `as` assertion (only `as const`), no non-null `!`, no floating promise, exhaustive `switch`.
5. Everything from outside (HTTP, storage, URL, deep links, SSE/WebSocket events, environment) is `unknown` until a Zod schema parses it.
6. API types and Zod schemas come from `@ostrom-hq/core`, generated from ostrom-core by Zodable: never hand-write an API entity type; a missing type is added to ostrom-core first.
7. Layers: `repositories/` (`XxxRepository` + `XxxRepositoryImpl`, Zod parse of every response) → `query/` (React Query) → `hooks/` (custom hooks are the ViewModels) → `components/`, `screens/`. Zustand only for client state, never a copy of server state.
8. A component never calls `fetch`, a repository or a store directly: it uses its hook.
9. Files: `PascalCase.tsx` components, `camelCase.ts` hooks and utilities, never kebab-case components.
10. All user-facing text through i18n keys, French and English.
11. A feature ships on web and mobile unless written otherwise; shared code lives in shared packages.
12. Tests: TDD, 100 % coverage thresholds (lines, branches, functions, statements) enforced by the test runner; Testing Library for components; end-to-end against the real server (Playwright web, Maestro app).
13. Never log tokens, prompts or response bodies; no secret in the repository or in CI in clear.
14. CI: typecheck, lint, tests with coverage and end-to-end on every pull request; actions pinned by SHA.
15. Git: one branch per topic in its own worktree, never push to `main`, Conventional Commits in English, squash-merge after green CI and a review with nothing blocking; evidence in the pull request, never in a shared status file.

## Coordination

Agents working at night read `~/git/ostrom-hq/.coord/BRIEF.md` first, log their work in `.coord/JOURNAL.md` and put
questions in `.coord/QUESTIONS.md` with their recommendation, then continue with it. Never modify another agent's
branch or worktree, never kill a process you did not start.
