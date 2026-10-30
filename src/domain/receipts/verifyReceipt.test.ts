import vectors from "./__fixtures__/receipts.json"
import {parseEd25519PublicKeyPem} from "./publicKey"
import {verifyReceipt} from "./verifyReceipt"

const key = parseEd25519PublicKeyPem(vectors.key.publicKeySpkiPem)

function signedCopy(): Record<string, unknown> {
    return {...vectors.receipts[0]!.receipt}
}

describe("verifyReceipt (receipts signed by the prototype)", () => {
    it.each(vectors.receipts)("$name verifies", ({receipt, sha256}) => {
        expect(verifyReceipt(receipt, key)).toEqual({ok: true, digest: sha256, kid: key.kid})
    })

    it("sees a modified receipt", () => {
        const receipt = {...signedCopy(), initiator: "someone-else"}
        expect(verifyReceipt(receipt, key)).toMatchObject({ok: false, reason: "digest-mismatch"})
    })

    it("sees a forged signature over the right digest", () => {
        const original = signedCopy()
        const signature = String(original["signature"])
        const forged = signature.replace(/sig=[A-Za-z0-9+/]{4}/, "sig=AAAA")
        expect(verifyReceipt({...original, signature: forged}, key)).toMatchObject({ok: false, reason: "bad-signature"})
    })

    it("refuses a receipt signed by another key", () => {
        const original = signedCopy()
        const signature = String(original["signature"]).replace(/kid=[0-9a-f]+/, "kid=0000000000000000")
        expect(verifyReceipt({...original, signature}, key)).toMatchObject({ok: false, reason: "unknown-key"})
    })

    it("refuses another algorithm", () => {
        const original = signedCopy()
        const signature = String(original["signature"]).replace(/^ed25519;/, "rsa;")
        expect(verifyReceipt({...original, signature}, key)).toMatchObject({ok: false, reason: "unknown-algorithm"})
    })

    it("refuses a malformed signature field", () => {
        expect(verifyReceipt({...signedCopy(), signature: "ed25519;kid"}, key)).toMatchObject({
            ok: false,
            reason: "malformed-signature",
        })
        const original = String(signedCopy()["signature"])
        for (const sig of ["%%%", "AAAA"]) {
            const signature = original.replace(/sig=.*$/, `sig=${sig}`)
            expect(verifyReceipt({...signedCopy(), signature}, key)).toMatchObject({
                ok: false,
                reason: "malformed-signature",
            })
        }
    })

    it("refuses a receipt without signature, or something that is not a receipt", () => {
        const {signature: _signature, ...unsigned} = signedCopy()
        expect(verifyReceipt(unsigned, key)).toEqual({ok: false, reason: "missing-signature"})
        expect(verifyReceipt([], key)).toEqual({ok: false, reason: "not-an-object"})
        expect(verifyReceipt(null, key)).toEqual({ok: false, reason: "not-an-object"})
    })
})
