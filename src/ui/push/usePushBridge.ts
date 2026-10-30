// SPDX-License-Identifier: AGPL-3.0-only
import {useRouter} from "expo-router"
import {useEffect} from "react"
import {useTranslation} from "react-i18next"
import {PushDataSchema} from "@/api/contract/notifications"
import {useDeviceRegistration} from "@/query/notifications"
import {useServices} from "@/services/AppServices"

/**
 * While signed in: registers this phone for push, and opens the request of a tapped notification.
 * Only an Ostrom payload (`{kind: "ask", askId}`) opens anything; any other data in a push is ignored.
 */
export function usePushBridge(): void {
    const {push} = useServices()
    const router = useRouter()
    const {i18n} = useTranslation()
    useDeviceRegistration(i18n.language)
    useEffect(() => {
        const open = (data: unknown): void => {
            const parsed = PushDataSchema.safeParse(data)
            if (parsed.success) router.push(`/ask/${parsed.data.askId}`)
        }
        void push.launchData().then(open)
        return push.onOpen(open)
    }, [push, router])
}
