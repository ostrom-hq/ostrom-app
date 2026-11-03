// SPDX-License-Identifier: AGPL-3.0-only
// `ostrom://auth/callback`, `/auth/callback` or `ostrom:///auth/callback`, with or without a query, a fragment or a
// final slash.
const SIGN_IN_RETURN = /^(?:[a-z][a-z0-9+.-]*:\/\/)?\/*auth\/callback\/?(?:[?#]|$)/i

/**
 * What Expo Router opens for a link the system hands the app (`src/app/+native-intent.ts`). The sign-in return
 * (`ostrom://auth/callback`, `REDIRECT_URI`) is never routed: only the sign-in in progress reads it, through its browser session, and a
 * return the app did not ask for connects nothing (AUDIT-SECU-APP-2 A4). While the app runs it opens no screen
 * (`null`); when it launches the app, the app opens on its first screen. Every other link routes as it came.
 */
export function redirectSystemPath({path, initial}: {readonly path: string; readonly initial: boolean}): string | null {
    if (!SIGN_IN_RETURN.test(path)) return path
    return initial ? "/" : null
}
