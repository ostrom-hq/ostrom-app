/** @type {import('jest').Config} */
module.exports = {
    preset: "jest-expo",
    setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
    moduleNameMapper: {"^@/(.*)$": "<rootDir>/src/$1"},
    collectCoverageFrom: ["src/**/*.{ts,tsx}", "!src/**/*.test.{ts,tsx}", "!src/**/*.d.ts"],
    // Lyes, 30/10: 100 % of lines, branches, functions and statements, enforced by the CI.
    coverageThreshold: {global: {lines: 100, branches: 100, functions: 100, statements: 100}},
}
