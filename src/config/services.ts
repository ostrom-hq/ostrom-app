// SPDX-License-Identifier: AGPL-3.0-only
import Constants from "expo-constants"
import * as Clipboard from "expo-clipboard"
import * as Crypto from "expo-crypto"
import * as Linking from "expo-linking"
import * as WebBrowser from "expo-web-browser"
import {Platform} from "react-native"
import {z} from "zod"
import type {HttpClient} from "@/api/http/HttpClient"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {AccountRepositoryImpl} from "@/repositories/AccountRepositoryImpl"
import {ActivityRepositoryImpl} from "@/repositories/ActivityRepositoryImpl"
import {ApprovalsRepositoryImpl} from "@/repositories/ApprovalsRepositoryImpl"
import {NotificationsRepositoryImpl} from "@/repositories/NotificationsRepositoryImpl"
import {PinnedReceiptsRepository} from "@/repositories/PinnedReceiptsRepository"
import {ReceiptsRepositoryImpl} from "@/repositories/ReceiptsRepositoryImpl"
import {SpendingRepositoryImpl} from "@/repositories/SpendingRepositoryImpl"
import type {AppServices, Repositories} from "@/services/AppServices"
import {BiometricGateImpl} from "@/services/biometrics/BiometricGateImpl"
import {PushServiceImpl} from "@/services/push/PushServiceImpl"
import {GithubSignInImpl} from "@/services/signIn/GithubSignInImpl"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {UnlockKeyStoreImpl} from "@/services/unlock/UnlockKeyStoreImpl"
import {createSessionStore, type Session} from "@/stores/session"

const ExtraSchema = z.object({eas: z.object({projectId: z.string().min(1)})})

/** The EAS project id from `app.json` (`extra.eas.projectId`), which push tokens need; absent before the EAS project exists. */
export function easProjectId(extra: unknown): string | undefined {
    const parsed = ExtraSchema.safeParse(extra)
    return parsed.success ? parsed.data.eas.projectId : undefined
}

/** The repositories of a session: every call carries its token, and its organization when the server named one. */
export function repositoriesFor(session: Session): Repositories {
    const repositories = repositoriesOver(
        new HttpClientImpl({baseUrl: session.serverUrl, token: () => session.token, org: () => session.orgId}),
    )
    // Receipts are verified only with the keys this phone kept for the server (AUDIT-SECU-APP-2 A5).
    return {...repositories, receipts: new PinnedReceiptsRepository(repositories.receipts, new SecureStorageImpl(), session.serverUrl)}
}

/** The repositories over one HTTP client. */
export function repositoriesOver(http: HttpClient): Repositories {
    return {
        account: new AccountRepositoryImpl(http),
        activity: new ActivityRepositoryImpl(http),
        approvals: new ApprovalsRepositoryImpl(http),
        notifications: new NotificationsRepositoryImpl(http),
        receipts: new ReceiptsRepositoryImpl(http),
        spending: new SpendingRepositoryImpl(http),
    }
}

/** Opens a link outside the app: a link of the console opens the console (browser, or its app when installed). */
export async function openOutside(url: string): Promise<void> {
    await Linking.openURL(url)
}

/** Puts text on the system clipboard. */
export async function copyToClipboard(text: string): Promise<void> {
    await Clipboard.setStringAsync(text)
}

/** The composition root: the only place that picks the implementations. */
export function createAppServices(): AppServices {
    const storage = new SecureStorageImpl()
    const biometrics = new BiometricGateImpl()
    return {
        sessionStore: createSessionStore(storage),
        biometrics,
        unlock: new PhoneUnlockImpl({keys: new UnlockKeyStoreImpl(storage), biometrics}),
        push: new PushServiceImpl({projectId: easProjectId(Constants.expoConfig?.extra), platform: Platform.OS}),
        signIn: new GithubSignInImpl({
            openAuthSession: (url, redirectUri) => WebBrowser.openAuthSessionAsync(url, redirectUri),
            randomBytes: (length) => Crypto.getRandomBytes(length),
            accountOf: (serverUrl, token) => new AccountRepositoryImpl(new HttpClientImpl({baseUrl: serverUrl, token: () => token})),
        }),
        repositoriesFor,
        openUrl: openOutside,
        copyText: copyToClipboard,
    }
}
