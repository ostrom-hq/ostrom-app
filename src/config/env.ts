// Expo inlines `process.env.EXPO_PUBLIC_*` at build time; only this exact expression is replaced.
declare const process: {readonly env: {readonly EXPO_PUBLIC_OSTROM_API_URL?: string | undefined}}

const HOSTED_SERVER_URL = "https://app.ostrom.run"

/** The server the login screen proposes: the one the build was made for, else the hosted Ostrom. */
export function serverUrlFrom(configured: string | undefined): string {
    return configured === undefined || configured === "" ? HOSTED_SERVER_URL : configured
}

export const DEFAULT_SERVER_URL = serverUrlFrom(process.env.EXPO_PUBLIC_OSTROM_API_URL)
