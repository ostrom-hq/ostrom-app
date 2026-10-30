import {type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {Device, NotificationSettings} from "@/api/contract/notifications"
import {useRepositories, useServices} from "@/services/AppServices"

export const notificationKeys = {
    /** This phone's registration for push: the device, or `null` when push is off or unavailable here. */
    device: (locale: string) => ["device", locale] as const,
    settings: ["notifications", "settings"] as const,
}

/**
 * Registers this phone for push when the person allows it, again in a new language, and again when the app comes back
 * to the foreground an hour later: a permission given since in the system settings, or a rotated token, reaches the
 * server without a restart.
 */
export function useDeviceRegistration(locale: string): UseQueryResult<Device | null> {
    const {push} = useServices()
    const {notifications} = useRepositories()
    return useQuery({
        queryKey: notificationKeys.device(locale),
        queryFn: async () => {
            const permission = await push.register()
            if (permission.kind !== "granted") return null
            return notifications.registerDevice({...permission.address, provider: "expo", locale})
        },
        staleTime: 60 * 60_000,
        retry: false,
    })
}

export function useNotificationSettings(): UseQueryResult<NotificationSettings> {
    const {notifications} = useRepositories()
    return useQuery({queryKey: notificationKeys.settings, queryFn: () => notifications.settings()})
}

const SAVE_KEY = ["notifications", "save"] as const

/**
 * Saves at once on screen. Two quick switches send two saves: only the last one to settle writes the screen, so the
 * answer of the first never flips the second back. A refusal reads the settings again from the server.
 */
export function useSaveNotificationSettings(): UseMutationResult<NotificationSettings, Error, NotificationSettings> {
    const {notifications} = useRepositories()
    const queryClient = useQueryClient()
    const last = (): boolean => queryClient.isMutating({mutationKey: SAVE_KEY}) === 1
    return useMutation({
        mutationKey: SAVE_KEY,
        mutationFn: (settings: NotificationSettings) => notifications.saveSettings(settings),
        onMutate: async (settings) => {
            await queryClient.cancelQueries({queryKey: notificationKeys.settings})
            queryClient.setQueryData(notificationKeys.settings, settings)
        },
        onError: () => {
            if (last()) void queryClient.invalidateQueries({queryKey: notificationKeys.settings})
        },
        onSuccess: (saved) => {
            if (last()) queryClient.setQueryData(notificationKeys.settings, saved)
        },
    })
}
