// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Runs a row's signature check after the frame that shows the row, so that a long list appears at once and each
 * verification (a hash and an Ed25519 check) comes after. Gives back what cancels it, for a row that leaves first.
 */
export function scheduleCheck(task: () => void): () => void {
    const timer = setTimeout(task, 0)
    return () => {
        clearTimeout(timer)
    }
}
