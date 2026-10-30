import key from "./__fixtures__/key.json"
import {bytesToHex} from "./bytes"
import {parseEd25519PublicKeyPem} from "./publicKey"

describe("parseEd25519PublicKeyPem", () => {
    it("reads the raw key and the key id of an SPKI PEM", () => {
        const parsed = parseEd25519PublicKeyPem(key.publicKeySpkiPem)
        expect(bytesToHex(parsed.raw)).toBe(key.publicKeyRawHex)
        expect(parsed.kid).toBe(key.kid)
    })

    it("refuses a PEM that is not a public key", () => {
        expect(() => parseEd25519PublicKeyPem(key.privateKeyPkcs8Pem)).toThrow("not an Ed25519 public key")
    })

    it("refuses a public key of another algorithm", () => {
        const notEd25519 = "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VuAyEAFaTHPoIFP1zbbrrHDBIDpfCW9zXjiXCkuGwP9LU8puM=\n-----END PUBLIC KEY-----\n"
        expect(() => parseEd25519PublicKeyPem(notEd25519)).toThrow("not an Ed25519 public key")
    })
})
