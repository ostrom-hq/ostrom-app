// @ts-check
import js from "@eslint/js"
import globals from "globals"
import reactHooks from "eslint-plugin-react-hooks"
import tseslint from "typescript-eslint"

export default tseslint.config(
    {
        ignores: [
            "node_modules/**",
            ".expo/**",
            "coverage/**",
            "dist/**",
            "ios/**",
            "android/**",
            // Generated from contract/openapi.yaml by `npm run gen:api`; `gen:check` in the CI keeps it equal to the copy.
            "src/api/gen/**",
            "expo-env.d.ts",
            "*.config.js",
            "*.config.mjs",
            "jest.setup.ts",
            "openapi-ts.config.ts",
        ],
    },
    {
        linterOptions: {
            // No eslint-disable comments: a rule that does not fit is changed here, in review.
            noInlineConfig: true,
            reportUnusedDisableDirectives: "error",
        },
    },
    js.configs.recommended,
    ...tseslint.configs.strictTypeChecked,
    ...tseslint.configs.stylisticTypeChecked,
    reactHooks.configs.flat["recommended-latest"],
    {
        languageOptions: {
            globals: {...globals.node},
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
        // ostrom-core docs/CONVENTIONS.md § 13.2.
        rules: {
            "@typescript-eslint/no-explicit-any": "error",
            "@typescript-eslint/no-non-null-assertion": "error",
            "@typescript-eslint/ban-ts-comment": [
                "error",
                {"ts-ignore": true, "ts-expect-error": true, "ts-nocheck": true, "ts-check": false},
            ],
            // Only `as const`: data from outside is parsed with zod, never asserted.
            "@typescript-eslint/consistent-type-assertions": ["error", {assertionStyle: "never"}],
            "@typescript-eslint/consistent-type-imports": ["error", {fixStyle: "inline-type-imports"}],
            "@typescript-eslint/consistent-type-exports": "error",
            "@typescript-eslint/consistent-type-definitions": ["error", "interface"],
            "@typescript-eslint/explicit-function-return-type": ["error", {allowExpressions: true}],
            "@typescript-eslint/switch-exhaustiveness-check": "error",
            "@typescript-eslint/no-floating-promises": "error",
            "@typescript-eslint/no-unused-vars": ["error", {argsIgnorePattern: "^_", varsIgnorePattern: "^_"}],
            "@typescript-eslint/strict-boolean-expressions": "error",
            "@typescript-eslint/no-import-type-side-effects": "error",
            "@typescript-eslint/restrict-template-expressions": ["error", {allowNumber: true}],
            "no-console": ["error", {allow: ["warn", "error"]}],
            eqeqeq: ["error", "always"],
        },
    },
    {
        files: ["**/*.test.{ts,tsx}", "src/test/**/*.{ts,tsx}"],
        languageOptions: {globals: {...globals.jest}},
    },
)
