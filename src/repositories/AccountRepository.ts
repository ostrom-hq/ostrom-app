import type {Me, SessionToken, TokenExchangeRequest, Unlock} from "@/api/contract/account"
import type {MyOrganization} from "@/api/contract/organizations"

export interface AccountRepository {
    me(): Promise<Me>
    /** The organizations the person may act in, to choose the one the calls name (`Ostrom-Org`). */
    organizations(): Promise<readonly MyOrganization[]>
    /** Trades the one-time code of the sign-in deep link for a session token. */
    exchangeCode(request: TokenExchangeRequest): Promise<SessionToken>
    /**
     * Records that the person just passed the phone's biometrics: the server takes a no for 5 minutes after, and a yes
     * only to the request named here, within a minute and once (Q-APP-STEPUP-RISK).
     */
    unlock(unlockKey: string, requestId?: string): Promise<Unlock>
    /** Revokes the token of this session on the server. */
    logout(): Promise<void>
}
