import type {z} from "zod"
import {zAuthUser, zMe, zMobileTokenBody, zMobileTokenResponse} from "@/api/gen/zod.gen"

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

export const SessionTokenSchema = zMobileTokenResponse
export type SessionToken = z.output<typeof zMobileTokenResponse>
