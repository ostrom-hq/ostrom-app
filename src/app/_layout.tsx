import "@/config/i18n"
import {createAppServices} from "@/config/services"
import {AppRoot} from "@/ui/AppRoot"

const services = createAppServices()

export default function RootLayout(): React.JSX.Element {
    return <AppRoot services={services} />
}
