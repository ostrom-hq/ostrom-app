import {type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {Device, NotificationSettings} from "@/api/contract/notifications"
import {useRepositories, useServices} from "@/services/AppServices"

export const notificationKeys = {
    /** This phone's registration for push: the device, or `null` when push is off or unavailable here. */
    device: ["device"] as const,
    settings: ["notifications", "settings"] as const,
}

/** Registers this phone for push once per session, when the person allows it. */
export function useDeviceRegistration(locale: string): UseQueryResult<Device | null> {
    const {push} = useServices()
    const {notifications} = useRepositories()
    return useQuery({
        queryKey: notificationKeys.device,
        queryFn: async () => {
            const permission = await push.register()
            if (permission.kind !== "granted") return null
            return notifications.registerDevice({...permission.address, provider: "expo", locale})
        },
        staleTime: Infinity,
        retry: false,
    })
}

export function useNotificationSettings(): UseQueryResult<NotificationSettings> {
    const {notifications} = useRepositories()
    return useQuery({queryKey: notificationKeys.settings, queryFn: () => notifications.settings()})
}

interface SaveContext {
    readonly previous: NotificationSettings | undefined
}

/** Saves at once on screen, and puts the previous settings back if the server refuses. */
export function useSaveNotificationSettings(): UseMutationResult<NotificationSettings, Error, NotificationSettings, SaveContext> {
    const {notifications} = useRepositories()
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (settings: NotificationSettings) => notifications.saveSettings(settings),
        onMutate: async (settings): Promise<SaveContext> => {
            await queryClient.cancelQueries({queryKey: notificationKeys.settings})
            const previous = queryClient.getQueryData<NotificationSettings>(notificationKeys.settings)
            queryClient.setQueryData(notificationKeys.settings, settings)
            return {previous}
        },
        onError: (_error, _settings, context) => {
            queryClient.setQueryData(notificationKeys.settings, context?.previous)
        },
        onSuccess: (saved) => {
            queryClient.setQueryData(notificationKeys.settings, saved)
        },
    })
}
