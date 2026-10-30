import * as Notifications from "expo-notifications"
import {PushServiceImpl} from "./PushServiceImpl"

jest.mock("expo-notifications", () => ({
    PermissionStatus: {GRANTED: "granted", DENIED: "denied", UNDETERMINED: "undetermined"},
    setNotificationHandler: jest.fn(),
    getPermissionsAsync: jest.fn(),
    requestPermissionsAsync: jest.fn(),
    getExpoPushTokenAsync: jest.fn(),
    addNotificationResponseReceivedListener: jest.fn(),
    getLastNotificationResponseAsync: jest.fn(),
    clearLastNotificationResponse: jest.fn(),
    setNotificationChannelAsync: jest.fn(() => Promise.resolve(null)),
    AndroidImportance: {HIGH: 6},
}))

const mocked = jest.mocked(Notifications)
// Read before any test clears the mocks: the handler is set once, when the module loads.
const [handler] = mocked.setNotificationHandler.mock.calls[0] ?? []
const PROJECT = "00000000-0000-0000-0000-000000000000"

function permission(granted: boolean, canAskAgain = true): Notifications.NotificationPermissionsStatus {
    return {granted, canAskAgain, expires: "never", status: granted ? Notifications.PermissionStatus.GRANTED : Notifications.PermissionStatus.DENIED}
}

function response(data: Record<string, unknown>): Notifications.NotificationResponse {
    const content: Notifications.NotificationContent = {
        title: null,
        subtitle: null,
        body: null,
        data,
        categoryIdentifier: null,
        sound: null,
        threadIdentifier: null,
    }
    return {
        actionIdentifier: "expo.modules.notifications.actions.DEFAULT",
        notification: {date: 0, request: {identifier: "n1", content, trigger: null}},
    }
}

describe("PushServiceImpl", () => {
    beforeEach(() => {
        jest.clearAllMocks()
    })

    it("shows an Ostrom notification even while the app is open", async () => {
        await expect(handler?.handleNotification(response({}).notification)).resolves.toMatchObject({shouldShowBanner: true, shouldSetBadge: false})
    })

    it("gives this phone's address once the person allowed it", async () => {
        mocked.getPermissionsAsync.mockResolvedValue(permission(false))
        mocked.requestPermissionsAsync.mockResolvedValue(permission(true))
        mocked.getExpoPushTokenAsync.mockResolvedValue({type: "expo", data: "ExponentPushToken[abc]"})
        const push = new PushServiceImpl({projectId: PROJECT, platform: "ios"})
        await expect(push.register()).resolves.toEqual({kind: "granted", address: {pushToken: "ExponentPushToken[abc]", platform: "ios"}})
        expect(mocked.getExpoPushTokenAsync).toHaveBeenCalledWith({projectId: PROJECT})
    })

    it("does not ask again once allowed", async () => {
        mocked.getPermissionsAsync.mockResolvedValue(permission(true))
        mocked.getExpoPushTokenAsync.mockResolvedValue({type: "expo", data: "t"})
        await new PushServiceImpl({projectId: PROJECT, platform: "android"}).register()
        expect(mocked.requestPermissionsAsync).not.toHaveBeenCalled()
        // Android: the channel exists before the permission is read.
        expect(mocked.setNotificationChannelAsync).toHaveBeenCalledWith("requests", {name: "Requests", importance: 6})
    })

    it("respects a no, without asking again when the system forbids it", async () => {
        mocked.getPermissionsAsync.mockResolvedValue(permission(false, false))
        await expect(new PushServiceImpl({projectId: PROJECT, platform: "ios"}).register()).resolves.toEqual({kind: "denied"})
        expect(mocked.requestPermissionsAsync).not.toHaveBeenCalled()
    })

    it("works without push before the EAS project exists, or off a phone", async () => {
        await expect(new PushServiceImpl({projectId: undefined, platform: "ios"}).register()).resolves.toEqual({kind: "unavailable"})
        await expect(new PushServiceImpl({projectId: PROJECT, platform: "web"}).register()).resolves.toEqual({kind: "unavailable"})
        mocked.getPermissionsAsync.mockResolvedValue(permission(true))
        mocked.getExpoPushTokenAsync.mockRejectedValue(new Error("simulator"))
        await expect(new PushServiceImpl({projectId: PROJECT, platform: "ios"}).register()).resolves.toEqual({kind: "unavailable"})
    })

    it("passes on the data of a tapped notification, and of the one that launched the app", async () => {
        const remove = jest.fn()
        let tap: ((event: Notifications.NotificationResponse) => void) | undefined
        mocked.addNotificationResponseReceivedListener.mockImplementation((listener) => {
            tap = listener
            return {remove}
        })
        const listener = jest.fn()
        const push = new PushServiceImpl({projectId: PROJECT, platform: "ios"})
        const unsubscribe = push.onOpen(listener)
        tap?.(response({kind: "approval", approvalId: "appr_0001"}))
        expect(listener).toHaveBeenCalledWith({kind: "approval", approvalId: "appr_0001"})
        unsubscribe()
        expect(remove).toHaveBeenCalled()

        mocked.getLastNotificationResponseAsync.mockResolvedValue(response({kind: "approval", approvalId: "appr_0002"}))
        await expect(push.launchData()).resolves.toEqual({kind: "approval", approvalId: "appr_0002"})
        expect(mocked.clearLastNotificationResponse).toHaveBeenCalledTimes(1)
        mocked.getLastNotificationResponseAsync.mockResolvedValue(null)
        await expect(push.launchData()).resolves.toBeUndefined()
        expect(mocked.clearLastNotificationResponse).toHaveBeenCalledTimes(1)
    })
})
