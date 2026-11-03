// SPDX-License-Identifier: AGPL-3.0-only
import {redirectSystemPath} from "./signInReturn"

describe("redirectSystemPath", () => {
    it("never routes the sign-in return: the sign-in in progress reads it, and nothing else", () => {
        const back = "ostrom://auth/callback?code=c&state=s"
        // The app is running: the link opens no screen.
        expect(redirectSystemPath({path: back, initial: false})).toBeNull()
        expect(redirectSystemPath({path: "/auth/callback?code=c", initial: false})).toBeNull()
        expect(redirectSystemPath({path: "ostrom://auth/callback/", initial: false})).toBeNull()
        // The link launched the app: it opens on its first screen, signed in or not as it was.
        expect(redirectSystemPath({path: back, initial: true})).toBe("/")
        expect(redirectSystemPath({path: "ostrom:///auth/callback#x", initial: true})).toBe("/")
    })

    it("routes every other link as it came", () => {
        expect(redirectSystemPath({path: "ostrom://request/r_1", initial: false})).toBe("ostrom://request/r_1")
        expect(redirectSystemPath({path: "ostrom://auth/callbacks", initial: true})).toBe("ostrom://auth/callbacks")
        expect(redirectSystemPath({path: "/", initial: true})).toBe("/")
    })
})
