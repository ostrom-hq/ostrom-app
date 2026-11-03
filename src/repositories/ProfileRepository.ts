// SPDX-License-Identifier: AGPL-3.0-only
import type {Account} from "@/api/contract/account"

/** The person's own account, whatever organization the app acts in: what the app's token may read of it. */
export interface ProfileRepository {
    /** Who the person is, their e-mails and how they sign in (`github`, `google`, `email`, `oidc:<connection>`). */
    account(): Promise<Account>
}
