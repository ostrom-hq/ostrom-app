// SPDX-License-Identifier: AGPL-3.0-only
/** A value the server sent (optional fields of the contract are `nullish`). */
export function isPresent<T>(value: T | null | undefined): value is T {
    return value !== undefined && value !== null
}

/** A text worth showing: present and not empty. */
export function hasText(value: string | null | undefined): value is string {
    return isPresent(value) && value !== ""
}
