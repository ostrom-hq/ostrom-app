// SPDX-License-Identifier: AGPL-3.0-only
const path = require("node:path")
const {getDefaultConfig} = require("expo/metro-config")

const config = getDefaultConfig(__dirname)

// The E2E build (EXPO_PUBLIC_OSTROM_MOCK=1, set by the E2E workflow only) resolves the services of the build to the
// in-memory server's. Any other build never resolves that file: the mock server and its fixtures are not in its bundle.
// The E2E build against a real server (EXPO_PUBLIC_OSTROM_REAL_E2E=1, set by the e2e-real workflow only) resolves it to
// the real services with the token the CI made on that server.
const e2eServices =
    process.env.EXPO_PUBLIC_OSTROM_MOCK === "1"
        ? "buildServicesE2e.ts"
        : process.env.EXPO_PUBLIC_OSTROM_REAL_E2E === "1"
          ? "buildServicesReal.ts"
          : undefined
if (e2eServices !== undefined) {
    const real = path.join(__dirname, "src", "config", "buildServices.ts")
    const e2e = path.join(__dirname, "src", "config", e2eServices)
    config.resolver.resolveRequest = (context, moduleName, platform) => {
        const resolved = context.resolveRequest(context, moduleName, platform)
        return resolved.type === "sourceFile" && resolved.filePath === real ? {type: "sourceFile", filePath: e2e} : resolved
    }
}

module.exports = config
