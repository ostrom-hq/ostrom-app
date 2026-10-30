import {z} from "zod"
import {DateTimeSchema} from "./common"

export const UserSchema = z.object({
    login: z.string(),
    name: z.string().nullish(),
    avatarUrl: z.string().nullish(),
})
export type User = z.output<typeof UserSchema>

/** `GET /api/me`: who the token belongs to, and the organisation of the instance. */
export const MeSchema = UserSchema.extend({
    org: z.object({login: z.string(), name: z.string().nullish(), avatarUrl: z.string().nullish()}).nullish(),
})
export type Me = z.output<typeof MeSchema>

/**
 * `POST /api/auth/mobile/token`: the one-time code the server put in the `ostrom://auth/callback` deep link,
 * exchanged with the PKCE verifier only the app knows.
 */
export const TokenExchangeRequestSchema = z.object({
    code: z.string(),
    codeVerifier: z.string(),
    redirectUri: z.string(),
})
export type TokenExchangeRequest = z.output<typeof TokenExchangeRequestSchema>

export const SessionTokenSchema = z.object({
    token: z.string(),
    user: UserSchema,
    expiresAt: DateTimeSchema.nullish(),
})
export type SessionToken = z.output<typeof SessionTokenSchema>
