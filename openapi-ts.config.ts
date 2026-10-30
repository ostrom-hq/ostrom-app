/**
 * The API types and their zod schemas, generated from ostrom-core's `api/openapi.yaml`.
 * `contract/openapi.yaml` is that file at the commit pinned in `contract/CORE_SHA`; the CI checks the copy against
 * ostrom-core and the generated files against the copy. To move to a newer contract: copy the file at the new commit,
 * write its SHA, run `npm run gen:api` (the generator is run pinned through npx: it is not a dependency of the app).
 */
export default {
    input: "./contract/openapi.yaml",
    // No tsconfig lookup: the output must not depend on whether `expo/tsconfig.base` is installed.
    output: {path: "src/api/gen", tsConfigPath: null, importFileExtension: null},
    plugins: ["@hey-api/typescript", "zod"],
}
