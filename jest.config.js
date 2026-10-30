/** @type {import('jest').Config} */
const esmOnly = ["@noble"]

module.exports = {
    preset: "jest-expo",
    setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
    moduleNameMapper: {"^@/(.*)$": "<rootDir>/src/$1"},
    // jest-expo's list, plus the ESM-only packages Babel must turn into CommonJS for Jest.
    transformIgnorePatterns: [
        `/node_modules/(?!(.pnpm|react-native|@react-native|@react-native-community|expo|@expo|@expo-google-fonts|react-navigation|@react-navigation|@sentry/react-native|native-base|standard-navigation|${esmOnly.join("|")}))`,
        "/node_modules/react-native-reanimated/plugin/",
        "/node_modules/@react-native/babel-preset/",
    ],
    collectCoverageFrom: ["src/**/*.{ts,tsx}", "!src/**/*.test.{ts,tsx}", "!src/**/*.d.ts"],
    // Lyes, 30/10: 100 % of lines, branches, functions and statements, enforced by the CI.
    coverageThreshold: {global: {lines: 100, branches: 100, functions: 100, statements: 100}},
}
