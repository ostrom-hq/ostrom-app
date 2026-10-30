import "@/config/i18n"
import {createAppServices} from "@/config/services"
import {AppRoot} from "@/ui/AppRoot"

const services = createAppServices()

/** A request opened from a notification or a deep link sits on top of the tabs: Back leads to the inbox. */
export const unstable_settings = {anchor: "(tabs)"}

export default function RootLayout(): React.JSX.Element {
    return <AppRoot services={services} />
}
