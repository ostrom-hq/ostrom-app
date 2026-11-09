// SPDX-License-Identifier: AGPL-3.0-only
import {type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import {useCallback} from "react"
import type {Device, NotificationSettings} from "@/api/contract/notifications"
import {FEATURES} from "@/domain/capabilities/compatibility"
import {useFeature, useRepositories, useServices} from "@/services/AppServices"

export const notificationKeys = {
    /** This phone's registration for push: the device, or `null` when push is off or unavailable here. */
    device: (locale: string) => ["device", locale] as const,
    settings: ["notifications", "settings"] as const,
}

/**
 * Registers this phone for push when the person allows it, again in a new language, and again when the app comes back
 * to the foreground an hour later: a permission given since in the system settings, or a rotated token, reaches the
 * server without a restart. Nothing is registered with a server that does not serve push (`notifications.push.v1`).
 */
export function useDeviceRegistration(locale: string): UseQueryResult<Device | null> {
    const {push} = useServices()
    const {notifications} = useRepositories()
    const served = useFeature(FEATURES.push)
    return useQuery({
        queryKey: notificationKeys.device(locale),
        enabled: served,
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
    const served = useFeature(FEATURES.notifications)
    return useQuery({queryKey: notificationKeys.settings, enabled: served, queryFn: () => notifications.settings()})
}

const SAVE_KEY = ["notifications", "save"] as const

export interface NotificationSettingsEditor {
    /**
     * Applies a change to the settings as they stand in the cache, on screen at once, then saves them. Two quick
     * switches compose: the second applies to the first, never to the settings of an older render.
     */
    readonly change: (edit: (current: NotificationSettings) => NotificationSettings) => void
    /** The last save was refused: the settings are read again from the server. */
    readonly failed: boolean
}

/**
 * Saves at once on screen. Only the last save to settle writes the screen, so the answer of a first save never flips a
 * second back.
 */
export function useNotificationSettingsEditor(): NotificationSettingsEditor {
    const {notifications} = useRepositories()
    const queryClient = useQueryClient()
    const last = (): boolean => queryClient.isMutating({mutationKey: SAVE_KEY}) === 1
    const {mutate, isError} = useMutation({
        mutationKey: SAVE_KEY,
        mutationFn: (settings: NotificationSettings) => notifications.saveSettings(settings),
        onError: () => {
            if (last()) void queryClient.invalidateQueries({queryKey: notificationKeys.settings})
        },
        onSuccess: (saved) => {
            if (last()) queryClient.setQueryData(notificationKeys.settings, saved)
        },
    })
    const change = useCallback(
        (edit: (current: NotificationSettings) => NotificationSettings) => {
            void (async () => {
                // A read still on its way would land after the change and put the old settings back.
                await queryClient.cancelQueries({queryKey: notificationKeys.settings})
                const current = queryClient.getQueryData<NotificationSettings>(notificationKeys.settings)
                if (current === undefined) return
                const next = edit(current)
                queryClient.setQueryData(notificationKeys.settings, next)
                mutate(next)
            })()
        },
        [queryClient, mutate],
    )
    return {change, failed: isError}
}
