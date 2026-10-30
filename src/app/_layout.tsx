import "@/config/i18n"
import {servicesForBuild} from "@/config/buildServices"
import {MOCK_SERVER} from "@/config/env"
import {AppRoot} from "@/ui/AppRoot"

const services = servicesForBuild(MOCK_SERVER)

/** A request opened from a notification or a deep link sits on top of the tabs: Back leads to the inbox. */
export const unstable_settings = {anchor: "(tabs)"}

export default function RootLayout(): React.JSX.Element {
    return <AppRoot services={services} />
}
