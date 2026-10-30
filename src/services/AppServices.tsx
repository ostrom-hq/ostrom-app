import {createContext, type ReactNode, use, useMemo} from "react"
import type {StoreApi} from "zustand/vanilla"
import {useStore} from "zustand"
import {ApiError} from "@/api/http/ApiError"
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {ActivityRepository} from "@/repositories/ActivityRepository"
import type {ApprovalsRepository} from "@/repositories/ApprovalsRepository"
import type {NotificationsRepository} from "@/repositories/NotificationsRepository"
import type {ReceiptsRepository} from "@/repositories/ReceiptsRepository"
import type {SpendingRepository} from "@/repositories/SpendingRepository"
import type {Session, SessionState, SessionStore} from "@/stores/session"
import type {BiometricGate} from "./biometrics/BiometricGate"
import type {GithubSignIn} from "./signIn/GithubSignIn"

/** The repositories of one signed-in session. */
export interface Repositories {
    readonly account: AccountRepository
    readonly activity: ActivityRepository
    readonly approvals: ApprovalsRepository
    readonly notifications: NotificationsRepository
    readonly receipts: ReceiptsRepository
    readonly spending: SpendingRepository
}

/** Everything a screen may need from outside React, injected once at the root (tests inject fakes). */
export interface AppServices {
    readonly sessionStore: StoreApi<SessionStore>
    readonly signIn: GithubSignIn
    readonly biometrics: BiometricGate
    readonly repositoriesFor: (session: Session) => Repositories
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

function refuse(): Promise<never> {
    return Promise.reject(new ApiError({kind: "unauthorized"}))
}

/**
 * What a screen still mounted after sign-out talks to: every call is refused as unauthorized,
 * so nothing reaches the server without a session and the screen goes away with the guard.
 */
export const SIGNED_OUT_REPOSITORIES: Repositories = {
    account: {me: refuse, exchangeCode: refuse},
    activity: {page: refuse},
    approvals: {queue: refuse, ask: refuse, decide: refuse},
    notifications: {registerDevice: refuse, unregisterDevice: refuse, settings: refuse, saveSettings: refuse},
    receipts: {list: refuse, get: refuse, publicKey: refuse},
    spending: {report: refuse},
}

/** The repositories of the signed-in session; after sign-out, ones that refuse every call. */
export function useRepositories(): Repositories {
    const {repositoriesFor} = useServices()
    const state = useSessionState()
    const session = state.status === "signed-in" ? state.session : undefined
    return useMemo(() => (session === undefined ? SIGNED_OUT_REPOSITORIES : repositoriesFor(session)), [repositoriesFor, session])
}
