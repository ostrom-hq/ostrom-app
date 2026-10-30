import Constants from "expo-constants"
import * as Crypto from "expo-crypto"
import * as WebBrowser from "expo-web-browser"
import {Platform} from "react-native"
import {z} from "zod"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {AccountRepositoryImpl} from "@/repositories/AccountRepositoryImpl"
import {ActivityRepositoryImpl} from "@/repositories/ActivityRepositoryImpl"
import {ApprovalsRepositoryImpl} from "@/repositories/ApprovalsRepositoryImpl"
import {NotificationsRepositoryImpl} from "@/repositories/NotificationsRepositoryImpl"
import {ReceiptsRepositoryImpl} from "@/repositories/ReceiptsRepositoryImpl"
import {SpendingRepositoryImpl} from "@/repositories/SpendingRepositoryImpl"
import type {AppServices, Repositories} from "@/services/AppServices"
import {BiometricGateImpl} from "@/services/biometrics/BiometricGateImpl"
import {PushServiceImpl} from "@/services/push/PushServiceImpl"
import {GithubSignInImpl} from "@/services/signIn/GithubSignInImpl"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {createSessionStore, type Session} from "@/stores/session"

const ExtraSchema = z.object({eas: z.object({projectId: z.string().min(1)})})

/** The EAS project id from `app.json` (`extra.eas.projectId`), which push tokens need; absent before the EAS project exists. */
export function easProjectId(extra: unknown): string | undefined {
    const parsed = ExtraSchema.safeParse(extra)
    return parsed.success ? parsed.data.eas.projectId : undefined
}

/** The repositories of a session: every call carries its token. */
export function repositoriesFor(session: Session): Repositories {
    const http = new HttpClientImpl({baseUrl: session.serverUrl, token: () => session.token})
    return {
        account: new AccountRepositoryImpl(http),
        activity: new ActivityRepositoryImpl(http),
        approvals: new ApprovalsRepositoryImpl(http),
        notifications: new NotificationsRepositoryImpl(http),
        receipts: new ReceiptsRepositoryImpl(http),
        spending: new SpendingRepositoryImpl(http),
    }
}

/** The composition root: the only place that picks the implementations. */
export function createAppServices(): AppServices {
    return {
        sessionStore: createSessionStore(new SecureStorageImpl()),
        biometrics: new BiometricGateImpl(),
        push: new PushServiceImpl({projectId: easProjectId(Constants.expoConfig?.extra), platform: Platform.OS}),
        signIn: new GithubSignInImpl({
            openAuthSession: (url, redirectUri) => WebBrowser.openAuthSessionAsync(url, redirectUri),
            randomBytes: (length) => Crypto.getRandomBytes(length),
            accountOf: (serverUrl) =>
                new AccountRepositoryImpl(new HttpClientImpl({baseUrl: serverUrl, token: () => undefined})),
        }),
        repositoriesFor,
    }
}
