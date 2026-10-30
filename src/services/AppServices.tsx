import {createContext, type ReactNode, use, useMemo} from "react"
import type {StoreApi} from "zustand/vanilla"
import {useStore} from "zustand"
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

/** The repositories of the signed-in session; only screens behind the sign-in guard call it. */
export function useRepositories(): Repositories {
    const {repositoriesFor} = useServices()
    const state = useSessionState()
    const session = state.status === "signed-in" ? state.session : undefined
    const repositories = useMemo(
        () => (session === undefined ? undefined : repositoriesFor(session)),
        [repositoriesFor, session],
    )
    if (repositories === undefined) throw new Error("useRepositories while signed out")
    return repositories
}
