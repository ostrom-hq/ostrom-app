// SPDX-License-Identifier: AGPL-3.0-only
import {createContext, type ReactNode, use, useMemo} from "react"
import type {StoreApi} from "zustand/vanilla"
import {useStore} from "zustand"
import type {Capabilities} from "@/api/contract/capabilities"
import {ApiError} from "@/api/http/ApiError"
import {type Feature, isServed} from "@/domain/capabilities/compatibility"
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {ActivityRepository} from "@/repositories/ActivityRepository"
import type {ApprovalsRepository} from "@/repositories/ApprovalsRepository"
import type {CapabilitiesRepository} from "@/repositories/CapabilitiesRepository"
import type {NotificationsRepository} from "@/repositories/NotificationsRepository"
import type {ProfileRepository} from "@/repositories/ProfileRepository"
import type {ReceiptsRepository} from "@/repositories/ReceiptsRepository"
import type {SpendingRepository} from "@/repositories/SpendingRepository"
import type {WaitsRepository} from "@/repositories/WaitsRepository"
import type {ServerIncompatibility, Session, SessionState, SessionStore} from "@/stores/session"
import type {BiometricGate} from "./biometrics/BiometricGate"
import type {PushService} from "./push/PushService"
import type {GithubSignIn} from "./signIn/GithubSignIn"
import type {PhoneUnlock} from "./unlock/PhoneUnlock"

/** The repositories of one signed-in session. */
export interface Repositories {
    readonly account: AccountRepository
    readonly activity: ActivityRepository
    readonly approvals: ApprovalsRepository
    readonly capabilities: CapabilitiesRepository
    readonly notifications: NotificationsRepository
    readonly profile: ProfileRepository
    readonly receipts: ReceiptsRepository
    readonly spending: SpendingRepository
    readonly waits: WaitsRepository
}

/** Everything a screen may need from outside React, injected once at the root (tests inject fakes). */
export interface AppServices {
    readonly sessionStore: StoreApi<SessionStore>
    readonly signIn: GithubSignIn
    readonly biometrics: BiometricGate
    /** The biometrics before an answer, proven to the server with the unlock key (server#75). */
    readonly unlock: PhoneUnlock
    readonly push: PushService
    readonly repositoriesFor: (session: Session) => Repositories
    /** Opens a link outside the app: a link of the console opens the console (browser, or its app when installed). */
    readonly openUrl: (url: string) => Promise<void>
    /** Puts text on the clipboard (a link to open by hand when it could not be opened). */
    readonly copyText: (text: string) => Promise<void>
    /**
     * Called when the first useful screen, the inbox list, is laid out. The E2E build logs it for the performance pass
     * (`scripts/perf-measure.sh`); no other build sets it.
     */
    readonly onFirstUsefulScreen?: () => void
    /**
     * Called when a tab is pressed, with its route's name (`index`, `history`, `activity`…). The E2E build logs it: the
     * performance pass times a list from this press to its layout, on the phone's clock; no other build sets it.
     */
    readonly onTabPress?: (tab: string) => void
    /** Called when a long list (inbox, activity, history) is laid out with its rows; logged by the E2E build, as above. */
    readonly onListShown?: (list: "inbox" | "activity" | "history") => void
}

const ServicesContext = createContext<AppServices | undefined>(undefined)

export function ServicesProvider({services, children}: {services: AppServices; children: ReactNode}): React.JSX.Element {
    return <ServicesContext value={services}>{children}</ServicesContext>
}

export function useServices(): AppServices {
    const services = use(ServicesContext)
    if (services === undefined) throw new Error("useServices outside ServicesProvider")
    return services
}

export function useSessionState(): SessionState {
    return useStore(useServices().sessionStore, (store) => store.state)
}

/** What the server of the signed-in session speaks and serves, when it has been read. */
export function useCapabilities(): Capabilities | undefined {
    const state = useSessionState()
    return state.status === "signed-in" ? state.session.capabilities : undefined
}

/**
 * Whether the server serves a feature, to show the function that needs it. True while the capabilities are unknown (never
 * read, or the server unreachable): a function is hidden only by a server that says it does not serve it.
 */
export function useFeature(feature: Feature): boolean {
    return isServed(useCapabilities(), feature)
}

/** Set when the server of the session speaks another API version than this app: the app blocks. */
export function useIncompatibleServer(): ServerIncompatibility | undefined {
    return useStore(useServices().sessionStore, (store) => store.incompatible)
}

function refuse(): Promise<never> {
    return Promise.reject(new ApiError({kind: "unauthorized"}))
}

/**
 * What a screen still mounted after sign-out talks to: every call is refused as unauthorized,
 * so nothing reaches the server without a session and the screen goes away with the guard.
 */
export const SIGNED_OUT_REPOSITORIES: Repositories = {
    account: {me: refuse, organizations: refuse, exchangeCode: refuse, unlock: refuse, logout: refuse},
    activity: {page: refuse},
    approvals: {queue: refuse, view: refuse, answer: refuse},
    capabilities: {read: refuse},
    notifications: {registerDevice: refuse, unregisterDevice: refuse, settings: refuse, saveSettings: refuse},
    profile: {account: refuse},
    receipts: {list: refuse, get: refuse, keys: refuse},
    spending: {report: refuse},
    waits: {list: refuse, answer: refuse},
}

/** The repositories of the signed-in session; after sign-out, ones that refuse every call. */
export function useRepositories(): Repositories {
    const {repositoriesFor} = useServices()
    const state = useSessionState()
    const session = state.status === "signed-in" ? state.session : undefined
    return useMemo(() => (session === undefined ? SIGNED_OUT_REPOSITORIES : repositoriesFor(session)), [repositoriesFor, session])
}
