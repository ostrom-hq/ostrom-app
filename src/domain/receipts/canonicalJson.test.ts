import canonicalVectors from "./__fixtures__/canonical.json"
import {canonicalJson} from "./canonicalJson"
import {sha256Hex} from "./bytes"

describe("canonicalJson (golden vectors of the prototype)", () => {
    it.each(canonicalVectors.cases)("$name", ({inputJson, canonical, sha256}) => {
        const value: unknown = JSON.parse(inputJson)
        const text = canonicalJson(value)
        expect(text).toBe(canonical)
        expect(sha256Hex(text)).toBe(sha256)
    })

    it("omits undefined fields and writes undefined array items as null", () => {
        expect(canonicalJson({a: 1, b: undefined, c: [1, undefined]})).toBe(canonicalVectors.codeOnly[0]!.canonical)
    })
})
