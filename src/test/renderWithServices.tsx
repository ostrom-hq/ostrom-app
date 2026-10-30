import {QueryClient, QueryClientProvider} from "@tanstack/react-query"
import {render} from "@testing-library/react-native"
import type {ReactElement} from "react"
import type {AppServices} from "@/services/AppServices"
import {ServicesProvider} from "@/services/AppServices"

/** A query client for tests: no retry, no garbage-collection timer left behind. */
export function testQueryClient(): QueryClient {
    return new QueryClient({defaultOptions: {queries: {retry: false, gcTime: Infinity}, mutations: {retry: false}}})
}

/** Renders a screen with fake services and a fresh server cache. */
export async function renderWithServices(ui: ReactElement, services: AppServices, client = testQueryClient()): Promise<QueryClient> {
    await render(
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{ui}</QueryClientProvider>
        </ServicesProvider>,
    )
    return client
}
