/**
 * PROVISIONAL CONTRACT. CONVENTIONS § 13.4 of ostrom-core: API types and their Zod schemas are generated from the
 * Kotlin contract (Zodable) and published as `@ostrom-hq/core`. That package does not exist yet (30/10/2026); until
 * it does, the app reads the server with the schemas of this folder, written from the prototype's routes
 * (mvp/server/src/yes/routes.ts, shared/src/types.ts) and, for what the prototype never had (mobile sign-in, devices,
 * activity, spending, notification settings), from the proposal in ostrom-hq/.coord/QUESTIONS.md.
 * Only `src/api/` and `src/repositories/` import this folder: swapping it for `@ostrom-hq/core` touches nothing else.
 * Optional fields are `.nullish()`: a Kotlin server may send `null` or omit the field.
 */
import {z} from "zod"

/** An ISO 8601 date-time with its offset (`Z` or `+02:00`). */
export const DateTimeSchema = z.iso.datetime({offset: true})

/** The error body every refusal carries (CONVENTIONS § 3.3): `type` is the stable key a client switches on. */
export const ErrorBodySchema = z.object({
    error: z.object({
        type: z.string(),
        message: z.string(),
    }),
})
export type ErrorBody = z.output<typeof ErrorBodySchema>
