import {base64ToBytes, bytesToHex, sha256Hex} from "./bytes"

describe("base64ToBytes", () => {
    it("decodes padded and unpadded base64", () => {
        expect(bytesToHex(base64ToBytes("TWFu"))).toBe("4d616e")
        expect(bytesToHex(base64ToBytes("TWE="))).toBe("4d61")
        expect(bytesToHex(base64ToBytes("TQ=="))).toBe("4d")
        expect(bytesToHex(base64ToBytes("TQ"))).toBe("4d")
        expect(bytesToHex(base64ToBytes("+/+/"))).toBe("fbffbf")
    })

    it("ignores whitespace, as in a PEM body", () => {
        expect(bytesToHex(base64ToBytes("TW\nFu "))).toBe("4d616e")
    })

    it("refuses anything that is not base64", () => {
        expect(() => base64ToBytes("TW$u")).toThrow("not base64")
        expect(() => base64ToBytes("T")).toThrow("not base64")
    })
})

describe("sha256Hex", () => {
    it("hashes the UTF-8 bytes of a text", () => {
        expect(sha256Hex("")).toBe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        expect(sha256Hex("é")).toBe("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c")
    })
})
