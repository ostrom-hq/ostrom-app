import type {Me, SessionToken, TokenExchangeRequest} from "@/api/contract/account"

export interface AccountRepository {
    me(): Promise<Me>
    /** Trades the one-time code of the sign-in deep link for a session token. */
    exchangeCode(request: TokenExchangeRequest): Promise<SessionToken>
}
