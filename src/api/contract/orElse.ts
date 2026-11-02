// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"

/**
 * A value of a newer server, outside the list `schema` knows, reads as `fallback` instead of failing what carries it: the
 * person decides on this evidence, with an app that may be months old.
 */
export function orElse<T extends z.ZodType<string>, F extends string>(
    schema: T,
    fallback: F,
): z.ZodUnion<readonly [T, z.ZodPipe<z.ZodString, z.ZodTransform<F, string>>]> {
    return z.union([schema, z.string().transform((): F => fallback)])
}
