import {base64Url, createPkce, pkceChallenge} from "./pkce"

describe("PKCE", () => {
    it("computes the S256 challenge of RFC 7636 appendix B", () => {
        expect(pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        )
    })

    it("encodes base64url without padding", () => {
        expect(base64Url(new Uint8Array([]))).toBe("")
        expect(base64Url(new Uint8Array([0xfb]))).toBe("-w")
        expect(base64Url(new Uint8Array([0xfb, 0xff]))).toBe("-_8")
        expect(base64Url(new Uint8Array([0xfb, 0xff, 0xbf]))).toBe("-_-_")
    })

    it("draws a 43-character verifier from 32 random bytes", () => {
        const randomBytes = jest.fn((length: number) => new Uint8Array(length).fill(7))
        const pkce = createPkce(randomBytes)
        expect(randomBytes).toHaveBeenCalledWith(32)
        expect(pkce.verifier).toHaveLength(43)
        expect(pkce.challenge).toBe(pkceChallenge(pkce.verifier))
    })
})
