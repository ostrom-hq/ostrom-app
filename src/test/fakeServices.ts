// SPDX-License-Identifier: AGPL-3.0-only
import type {AppServices, Repositories} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import type {PushService} from "@/services/push/PushService"
import type {GithubSignIn} from "@/services/signIn/GithubSignIn"
import type {SecureStorage} from "@/services/storage/SecureStorage"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import type {PhoneUnlock} from "@/services/unlock/PhoneUnlock"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {type Approval, ApprovalSchema, type ApprovalView} from "@/api/contract/approvals"
import {decidedApproval, pendingApproval, selfApproval} from "@/api/mocks/fixtures"
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
        account: {
            me: unexpected("account.me"),
            organizations: unexpected("account.organizations"),
            exchangeCode: unexpected("account.exchangeCode"),
            unlock: unexpected("account.unlock"),
            logout: unexpected("account.logout"),
        },
        activity: {page: unexpected("activity.page")},
        approvals: {
            queue: unexpected("approvals.queue"),
            view: unexpected("approvals.view"),
            answer: unexpected("approvals.answer"),
        },
        notifications: {
            registerDevice: unexpected("notifications.registerDevice"),
            unregisterDevice: unexpected("notifications.unregisterDevice"),
            settings: unexpected("notifications.settings"),
            saveSettings: unexpected("notifications.saveSettings"),
        },
        profile: {account: unexpected("profile.account")},
        receipts: {list: unexpected("receipts.list"), get: unexpected("receipts.get"), keys: unexpected("receipts.keys")},
        spending: {report: unexpected("spending.report")},
        ...overrides,
    }
}

export interface FakeServicesOptions {
    readonly storage?: SecureStorage
    readonly signIn?: GithubSignIn
    readonly biometrics?: BiometricGate
    /** By default, the real unlock over the fake biometrics and an empty key store: the app's own check alone. */
    readonly unlock?: PhoneUnlock
    readonly push?: PushService
    readonly repositories?: Repositories
    readonly openUrl?: (url: string) => Promise<void>
    readonly copyText?: (text: string) => Promise<void>
}

/** App services for tests: memory storage, scripted sign-in and biometrics, fake repositories. */
export function fakeServices(options: FakeServicesOptions = {}): AppServices {
    const repositories = options.repositories ?? fakeRepositories()
    const biometrics: BiometricGate = options.biometrics ?? {confirm: () => Promise.resolve("confirmed")}
    return {
        sessionStore: createSessionStore(options.storage ?? new MemoryStorage()),
        signIn: options.signIn ?? {signIn: () => Promise.resolve({kind: "cancelled"})},
        biometrics,
        unlock: options.unlock ?? new PhoneUnlockImpl({keys: new MemoryUnlockKeyStore(), biometrics}),
        push: options.push ?? {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: () => () => undefined,
            launchData: () => Promise.resolve(undefined),
        },
        repositoriesFor: () => repositories,
        openUrl: options.openUrl ?? (() => Promise.resolve()),
        copyText: options.copyText ?? (() => Promise.resolve()),
    }
}

/** The mock requests, parsed as the repositories return them. */
export const approvals: {readonly pending: Approval; readonly self: Approval; readonly decided: Approval} = {
    pending: ApprovalSchema.parse(pendingApproval),
    self: ApprovalSchema.parse(selfApproval),
    decided: ApprovalSchema.parse(decidedApproval),
}

/**
 * A request as the server shows it to `rev-b`: theirs when they asked for it, and theirs to approve unless `you`
 * says otherwise.
 */
export function viewOf(approval: Approval, you: Partial<ApprovalView["you"]> = {}): ApprovalView {
    const self = approval.askedBy?.includes(testSession.user.login) === true
    return {approval, you: {login: testSession.user.login, self, canApprove: true, ...you}}
}

/** Services already signed in, as after a launch with a kept session. */
export async function signedInServices(options: FakeServicesOptions = {}): Promise<AppServices> {
    const services = fakeServices(options)
    await services.sessionStore.getState().signIn(testSession)
    return services
}
