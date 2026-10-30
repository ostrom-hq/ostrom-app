import {DEFAULT_HOST} from "./analytics"
import {buildAnalyticsConfig, readAnalyticsConfig} from "./config"

declare const process: {readonly env: Record<string, string | undefined>}

describe("readAnalyticsConfig", () => {
    it("has no key and the EU host by default", () => {
        expect(readAnalyticsConfig({})).toEqual({apiKey: "", host: DEFAULT_HOST})
        expect(readAnalyticsConfig({EXPO_PUBLIC_POSTHOG_HOST: " "})).toEqual({apiKey: "", host: DEFAULT_HOST})
    })

    it("reads the key and host compiled into the build", () => {
        expect(
            readAnalyticsConfig({EXPO_PUBLIC_POSTHOG_KEY: " phc_1 ", EXPO_PUBLIC_POSTHOG_HOST: "https://ph.example"}),
        ).toEqual({apiKey: "phc_1", host: "https://ph.example"})
    })

    it("reads the build's variables by their literal names, the only form Expo inlines", () => {
        process.env["EXPO_PUBLIC_POSTHOG_KEY"] = "phc_build"
        try {
            expect(buildAnalyticsConfig()).toEqual({apiKey: "phc_build", host: DEFAULT_HOST})
        } finally {
            delete process.env["EXPO_PUBLIC_POSTHOG_KEY"]
        }
    })
})
