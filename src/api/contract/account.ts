import {z} from "zod"
import {zAuthUser, zMe, type zMobileTokenBody, zMobileTokenResponse} from "@/api/gen/zod.gen"

export const UserSchema = zAuthUser
export type User = z.output<typeof zAuthUser>

/** `GET /api/v1/auth/me`: who the token belongs to, their membership and organisation. */
export const MeSchema = zMe
export type Me = z.output<typeof zMe>

/**
 * `POST /api/v1/auth/mobile/token`: the one-time code the server put in the `ostrom://auth/callback` deep link,
 * exchanged with the PKCE verifier only the app knows.
 */
export type TokenExchangeRequest = z.output<typeof zMobileTokenBody>

/**
 * With the `unlockKey` server#75 adds, not in the contract copy yet (CORE-CONTRAT ports it after that merge): the key
 * the app keeps behind the biometrics and presents to `POST /auth/mobile/unlock` before an answer.
 */
export const SessionTokenSchema = zMobileTokenResponse.extend({unlockKey: z.string().min(1).optional()})
export type SessionToken = z.output<typeof SessionTokenSchema>

/** `POST /api/v1/auth/mobile/unlock` (server#75, written by hand until the contract has `unlockMobileApp`). */
export const UnlockSchema = z.object({authenticatedAt: z.string()})
export type Unlock = z.output<typeof UnlockSchema>
