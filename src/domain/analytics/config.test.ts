import {DEFAULT_HOST} from "./analytics"
import {readAnalyticsConfig} from "./config"

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
})
