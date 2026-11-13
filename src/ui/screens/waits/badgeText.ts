// SPDX-License-Identifier: AGPL-3.0-only
import type {WaitsBadge} from "./useWaitsViewModel"

/** The number in the badge: "12", "12+" when a further page exists, "99+" at most. */
export function badgeText({count, more}: WaitsBadge): string {
    if (count > 99) return "99+"
    return more ? `${String(count)}+` : String(count)
}
