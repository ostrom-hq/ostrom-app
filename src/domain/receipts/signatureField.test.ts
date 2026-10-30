import {parseSignatureField} from "./signatureField"

describe("parseSignatureField", () => {
    it("reads the algorithm, key id, digest and signature", () => {
        expect(parseSignatureField("ed25519;kid=ab12;sha256=ff00;sig=AAAA")).toEqual({
            algorithm: "ed25519",
            kid: "ab12",
            sha256: "ff00",
            signature: "AAAA",
        })
    })

    it("keeps an '=' inside a value", () => {
        expect(parseSignatureField("ed25519;kid=a;sha256=b;sig=AA==")).toMatchObject({signature: "AA=="})
    })

    it("returns undefined when a part is missing", () => {
        expect(parseSignatureField("ed25519;kid=a;sha256=b")).toBeUndefined()
        expect(parseSignatureField("ed25519;kid=a;sha256=b;sig")).toBeUndefined()
        expect(parseSignatureField("")).toBeUndefined()
    })
})
