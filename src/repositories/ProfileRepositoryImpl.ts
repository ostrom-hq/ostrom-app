import {type Account, AccountSchema} from "@/api/contract/account"
import type {HttpClient} from "@/api/http/HttpClient"
import type {ProfileRepository} from "./ProfileRepository"

export class ProfileRepositoryImpl implements ProfileRepository {
    constructor(private readonly http: HttpClient) {}

    account(): Promise<Account> {
        return this.http.send({method: "GET", path: "/api/v1/auth/me/account", response: AccountSchema})
    }
}
