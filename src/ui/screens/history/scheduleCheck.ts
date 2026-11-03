// SPDX-License-Identifier: AGPL-3.0-only

/** A stub that only lets the tests load, for a red run that fails on assertions: it runs nothing. */
export function scheduleCheck(_task: () => void): () => void {
    return () => undefined
}
