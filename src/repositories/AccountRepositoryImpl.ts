import {z} from "zod"
import {type Me, MeSchema, type SessionToken, SessionTokenSchema, type TokenExchangeRequest} from "@/api/contract/account"
import {type MyOrganization, MyOrganizationsSchema} from "@/api/contract/organizations"
import type {HttpClient} from "@/api/http/HttpClient"
import type {AccountRepository} from "./AccountRepository"

export class AccountRepositoryImpl implements AccountRepository {
    constructor(private readonly http: HttpClient) {}

    me(): Promise<Me> {
        return this.http.send({method: "GET", path: "/api/v1/auth/me", response: MeSchema})
    }

    async organizations(): Promise<readonly MyOrganization[]> {
        const {items} = await this.http.send({method: "GET", path: "/api/v1/me/organizations", response: MyOrganizationsSchema})
        return items
    }

    exchangeCode(request: TokenExchangeRequest): Promise<SessionToken> {
        return this.http.send({method: "POST", path: "/api/v1/auth/mobile/token", body: request, response: SessionTokenSchema})
    }

    /** Whatever the server answers with a success (204, `{}`) means the token is revoked. */
    async logout(): Promise<void> {
        await this.http.send({method: "POST", path: "/api/v1/auth/logout", response: z.unknown()})
    }
}
