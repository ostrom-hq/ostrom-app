# ostrom-app

The Ostrom mobile app (React Native, Expo, expo-router): where a person says yes.
A push notification says a request is waiting; the app shows its evidence (commits, repository, checks, who asked),
and the yes or the no is given behind Face ID or the device biometrics. It also shows the history and the signed
receipts, the agents' activity and the spending map.

## Develop

```
npm ci
npm start          # Expo dev server
npm run check      # typecheck + lint + tests, what the CI runs
```

## Layout

| Path | What lives there |
|---|---|
| `src/app/` | expo-router routes, thin: each file renders a screen |
| `src/ui/screens/<feature>/` | a screen and its view-model hook (`use<Screen>ViewModel`) |
| `src/ui/components/` | shared UI pieces |
| `src/ui/theme/` | the Signal tokens (garnet `#8E1B3A`), light and dark |
| `src/config/` | i18n, query client, environment |
| `src/i18n/` | locales: English is the source, every other locale is type-checked against it |

## Rules

- TypeScript at its strictest (`exactOptionalPropertyTypes`, `noUncheckedIndexedAccess`…) and ESLint
  `strict-type-checked`; no `any`, no `eslint-disable` (inline config is off), no type assertion on data:
  anything that crosses a boundary (HTTP, storage, notification payload) is read by a zod schema.
- No hard-coded text: `t("…")` with keys checked by the compiler.
- Garnet means "a person is awaited or has just decided", and nothing else; amber is budget, red is error.

## Licence

AGPL-3.0 (see `LICENSE`), except `ee/`, under the Ostrom Enterprise License (`ee/LICENSE`).
