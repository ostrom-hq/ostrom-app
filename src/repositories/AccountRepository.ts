import type {Me, SessionToken, TokenExchangeRequest} from "@/api/contract/account"
import type {MyOrganization} from "@/api/contract/organizations"

export interface AccountRepository {
    me(): Promise<Me>
    /** The organizations the person may act in, to choose the one the calls name (`Ostrom-Org`). */
    organizations(): Promise<readonly MyOrganization[]>
    /** Trades the one-time code of the sign-in deep link for a session token. */
    exchangeCode(request: TokenExchangeRequest): Promise<SessionToken>
    /** Revokes the token of this session on the server. */
    logout(): Promise<void>
}
