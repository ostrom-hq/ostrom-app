import {type Me, MeSchema, type SessionToken, SessionTokenSchema, type TokenExchangeRequest} from "@/api/contract/account"
import type {HttpClient} from "@/api/http/HttpClient"
import type {AccountRepository} from "./AccountRepository"

export class AccountRepositoryImpl implements AccountRepository {
    constructor(private readonly http: HttpClient) {}

    me(): Promise<Me> {
        return this.http.send({method: "GET", path: "/api/me", response: MeSchema})
    }

    exchangeCode(request: TokenExchangeRequest): Promise<SessionToken> {
        return this.http.send({method: "POST", path: "/api/auth/mobile/token", body: request, response: SessionTokenSchema})
    }
}
