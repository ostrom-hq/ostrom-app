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
            "expo-env.d.ts",
            "*.config.js",
            "*.config.mjs",
            "jest.setup.ts",
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
        rules: {
            "@typescript-eslint/consistent-type-imports": ["error", {fixStyle: "inline-type-imports"}],
            "@typescript-eslint/consistent-type-exports": "error",
            "@typescript-eslint/switch-exhaustiveness-check": "error",
            "@typescript-eslint/explicit-module-boundary-types": "error",
            "@typescript-eslint/no-unused-vars": ["error", {argsIgnorePattern: "^_", varsIgnorePattern: "^_"}],
            "@typescript-eslint/strict-boolean-expressions": ["error", {allowNullableBoolean: true}],
            "@typescript-eslint/no-import-type-side-effects": "error",
            "@typescript-eslint/restrict-template-expressions": ["error", {allowNumber: true}],
            "no-console": ["error", {allow: ["warn", "error"]}],
            eqeqeq: ["error", "always"],
        },
    },
    {
        // JSON.parse and fetch bodies are unknown until a zod schema has read them.
        files: ["src/**/*.{ts,tsx}"],
        rules: {
            "no-restricted-syntax": [
                "error",
                {
                    selector: "TSAsExpression > TSTypeReference[typeName.name!='const']",
                    message: "No type assertions on data: parse it with a zod schema instead.",
                },
            ],
        },
    },
    {
        files: ["**/*.test.{ts,tsx}", "src/test/**/*.{ts,tsx}"],
        languageOptions: {globals: {...globals.jest}},
        rules: {
            "no-restricted-syntax": "off",
            "@typescript-eslint/no-non-null-assertion": "off",
        },
    },
)
