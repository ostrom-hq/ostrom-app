/**
 * Two projects: `app` runs components and screens in the React Native environment (jest-expo);
 * `node` runs the API client, repositories and domain in plain Node, where MSW intercepts `fetch`.
 * @type {import('jest').Config}
 */
const moduleNameMapper = {"^@/(.*)$": "<rootDir>/src/$1"}
const nodeLayers = ["api", "domain", "repositories"]

// ESM-only packages Babel must turn into CommonJS for Jest.
const esmOnly = ["@noble", "msw", "@mswjs", "until-async", "rettime", "@open-draft", "headers-polyfill", "tough-cookie"]

module.exports = {
    collectCoverageFrom: ["src/**/*.{ts,tsx}", "!src/**/*.test.{ts,tsx}", "!src/**/*.d.ts"],
    // Lyes, 30/10: 100 % of lines, branches, functions and statements, enforced by the CI.
    coverageThreshold: {global: {lines: 100, branches: 100, functions: 100, statements: 100}},
    projects: [
        {
            displayName: "app",
            preset: "jest-expo",
            setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
            moduleNameMapper,
            testMatch: ["<rootDir>/src/**/*.test.{ts,tsx}"],
            testPathIgnorePatterns: ["/node_modules/", `<rootDir>/src/(${nodeLayers.join("|")})/`],
            // jest-expo's list, plus the ESM-only packages.
            transformIgnorePatterns: [
                `/node_modules/(?!(.pnpm|react-native|@react-native|@react-native-community|expo|@expo|@expo-google-fonts|react-navigation|@react-navigation|@sentry/react-native|native-base|standard-navigation|${esmOnly.join("|")}))`,
                "/node_modules/react-native-reanimated/plugin/",
                "/node_modules/@react-native/babel-preset/",
            ],
        },
        {
            displayName: "node",
            testEnvironment: "node",
            moduleNameMapper,
            testMatch: nodeLayers.map((layer) => `<rootDir>/src/${layer}/**/*.test.{ts,tsx}`),
            // Some of them ship `.mjs` files (rettime): Babel transforms those too.
            transform: {"^.+\\.[cm]?[jt]sx?$": "babel-jest"},
            moduleFileExtensions: ["ts", "tsx", "js", "mjs", "cjs", "jsx", "json"],
            transformIgnorePatterns: [`/node_modules/(?!(${esmOnly.join("|")})/)`],
        },
    ],
}
