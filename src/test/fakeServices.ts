import type {AppServices, Repositories} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import type {GithubSignIn} from "@/services/signIn/GithubSignIn"
import type {SecureStorage} from "@/services/storage/SecureStorage"
import {AskSchema, type Ask} from "@/api/contract/yes"
import {decidedAsk, pendingAsk, selfAsk} from "@/api/mocks/fixtures"
import {createSessionStore, type Session} from "@/stores/session"

/** A secure storage in memory. */
export class MemoryStorage implements SecureStorage {
    readonly items = new Map<string, string>()

    get(key: string): Promise<string | undefined> {
        return Promise.resolve(this.items.get(key))
    }

    set(key: string, value: string): Promise<void> {
        this.items.set(key, value)
        return Promise.resolve()
    }

    remove(key: string): Promise<void> {
        this.items.delete(key)
        return Promise.resolve()
    }
}

export const testSession: Session = {serverUrl: "https://ostrom.test", token: "ost_1", user: {login: "rev-b"}}

function unexpected(name: string): () => never {
    return () => {
        throw new Error(`unexpected call to ${name}`)
    }
}

/** Repositories whose every method fails the test unless the test overrides it. */
export function fakeRepositories(overrides: Partial<Repositories> = {}): Repositories {
    return {
        account: {me: unexpected("account.me"), exchangeCode: unexpected("account.exchangeCode")},
        activity: {page: unexpected("activity.page")},
        approvals: {queue: unexpected("approvals.queue"), ask: unexpected("approvals.ask"), decide: unexpected("approvals.decide")},
        notifications: {
            registerDevice: unexpected("notifications.registerDevice"),
            unregisterDevice: unexpected("notifications.unregisterDevice"),
            settings: unexpected("notifications.settings"),
            saveSettings: unexpected("notifications.saveSettings"),
        },
        receipts: {list: unexpected("receipts.list"), get: unexpected("receipts.get"), publicKey: unexpected("receipts.publicKey")},
        spending: {report: unexpected("spending.report")},
        ...overrides,
    }
}

export interface FakeServicesOptions {
    readonly storage?: SecureStorage
    readonly signIn?: GithubSignIn
    readonly biometrics?: BiometricGate
    readonly repositories?: Repositories
}

/** App services for tests: memory storage, scripted sign-in and biometrics, fake repositories. */
export function fakeServices(options: FakeServicesOptions = {}): AppServices {
    const repositories = options.repositories ?? fakeRepositories()
    return {
        sessionStore: createSessionStore(options.storage ?? new MemoryStorage()),
        signIn: options.signIn ?? {signIn: () => Promise.resolve({kind: "cancelled"})},
        biometrics: options.biometrics ?? {confirm: () => Promise.resolve("confirmed")},
        repositoriesFor: () => repositories,
    }
}

/** The mock requests, parsed as the repositories return them. */
export const asks: {readonly pending: Ask; readonly self: Ask; readonly decided: Ask} = {
    pending: AskSchema.parse(pendingAsk),
    self: AskSchema.parse(selfAsk),
    decided: AskSchema.parse(decidedAsk),
}

/** Services already signed in, as after a launch with a kept session. */
export async function signedInServices(options: FakeServicesOptions = {}): Promise<AppServices> {
    const services = fakeServices(options)
    await services.sessionStore.getState().signIn(testSession)
    return services
}
