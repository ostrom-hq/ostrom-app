// SPDX-License-Identifier: AGPL-3.0-only
import type {Wait, WaitFilter, WaitsPage, WaitValues} from "@/api/contract/waits"

/** The waits asked of me ("Waiting for you"). */
export interface WaitsRepository {
    /** The newest waits asked of me with this filter, or the page before `cursor`; `limit` is 1 to 100. */
    list(filter: WaitFilter, options?: {readonly cursor?: string | undefined; readonly limit?: number | undefined}): Promise<WaitsPage>
    /** Answers with an action and the values by field id; the first answer wins (409 once closed). */
    answer(id: string, action: string, values: WaitValues): Promise<Wait>
}
