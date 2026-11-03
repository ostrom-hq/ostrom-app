// SPDX-License-Identifier: AGPL-3.0-only
import * as ed from "@noble/ed25519"
import {sha256} from "@noble/hashes/sha2.js"
import type {ReceiptPublicKey} from "@/api/contract/receipts"
import {keyRotationMessage} from "@/domain/receipts/keyRotation"
import type {SecureStorage} from "@/services/storage/SecureStorage"
import {PinnedReceiptsRepository, RECEIPT_KEY_PINS} from "./PinnedReceiptsRepository"
import type {ReceiptsRepository} from "./ReceiptsRepository"

const SERVER = "https://ostrom.acme.dev"
const SPKI_PREFIX = Uint8Array.from([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00])

const seed = (fill: number): Uint8Array => new Uint8Array(32).fill(fill)

/** The instance key drawn from a seed, as `GET /receipts/keys` serves it; rotated from another seed when given. */
function served(fill: number, rotatedFrom?: number, signer: number | undefined = rotatedFrom): ReceiptPublicKey {
    const raw = ed.getPublicKey(seed(fill))
    const der = new Uint8Array([...SPKI_PREFIX, ...raw])
    const kid = Buffer.from(sha256(der)).toString("hex").slice(0, 16)
    const key: ReceiptPublicKey = {kid, alg: "Ed25519", publicKeyPem: `-----BEGIN PUBLIC KEY-----\n${Buffer.from(der).toString("base64")}\n-----END PUBLIC KEY-----\n`}
    if (rotatedFrom === undefined || signer === undefined) return key
    const signature = Buffer.from(ed.sign(keyRotationMessage({raw, kid}), seed(signer))).toString("base64")
    return {...key, rotation: {kid: served(rotatedFrom).kid, signature}}
}

class Storage implements SecureStorage {
    readonly items = new Map<string, string>()
    refuseWrites = false
    get(key: string): Promise<string | undefined> {
        return Promise.resolve(this.items.get(key))
    }
    set(key: string, value: string): Promise<void> {
        if (this.refuseWrites) return Promise.reject(new Error("keychain refused"))
        this.items.set(key, value)
        return Promise.resolve()
    }
    remove(key: string): Promise<void> {
        this.items.delete(key)
        return Promise.resolve()
    }
}

/** The receipts repository of a server that serves the given keys, one call after the other. */
function serving(...answers: (readonly ReceiptPublicKey[])[]): ReceiptsRepository {
    const keys = jest.fn<Promise<readonly ReceiptPublicKey[]>, []>()
    for (const answer of answers) keys.mockResolvedValueOnce(answer)
    return {list: jest.fn(), get: jest.fn(), keys}
}

const kids = (keys: readonly ReceiptPublicKey[]): string[] => keys.map((key) => key.kid)

describe("PinnedReceiptsRepository", () => {
    it("keeps the keys of its first view of a server, and refuses a key that comes later without a signed rotation", async () => {
        const storage = new Storage()
        const repository = new PinnedReceiptsRepository(serving([served(1)], [served(1), served(2)], [served(2)]), storage, SERVER)
        expect(kids(await repository.keys())).toEqual([served(1).kid])
        expect(storage.items.has(RECEIPT_KEY_PINS)).toBe(true)
        // A key the server swapped in: receipts it signs are not verified with it.
        expect(kids(await repository.keys())).toEqual([served(1).kid])
        expect(await repository.keys()).toEqual([])
    })

    it("takes a new key rotated by a key it keeps, and keeps it from then on", async () => {
        const storage = new Storage()
        await new PinnedReceiptsRepository(serving([served(1)]), storage, SERVER).keys()
        // Relaunched: the pins come from the keychain. C is rotated from B, which is rotated from A.
        const relaunched = new PinnedReceiptsRepository(serving([served(3, 2), served(2, 1)], [served(3)]), storage, SERVER)
        expect(kids(await relaunched.keys())).toEqual([served(3).kid, served(2).kid])
        expect(kids(await relaunched.keys())).toEqual([served(3).kid])
    })

    it("refuses a rotation signed by a key it does not keep, or by the wrong key", async () => {
        const storage = new Storage()
        const repository = new PinnedReceiptsRepository(
            serving([served(1)], [served(1), served(2, 4), served(3, 1, 5)]),
            storage,
            SERVER,
        )
        await repository.keys()
        expect(kids(await repository.keys())).toEqual([served(1).kid])
    })

    it("keeps each server's first view apart", async () => {
        const storage = new Storage()
        await new PinnedReceiptsRepository(serving([served(1)]), storage, SERVER).keys()
        expect(kids(await new PinnedReceiptsRepository(serving([served(2)]), storage, "https://other.dev").keys())).toEqual([served(2).kid])
        expect(kids(await new PinnedReceiptsRepository(serving([served(2)]), storage, SERVER).keys())).toEqual([])
    })

    it("leaves out a key it cannot read, and pins nothing from a view without a usable key", async () => {
        const storage = new Storage()
        const other: ReceiptPublicKey = {kid: "k", alg: "RS256", publicKeyPem: "-----BEGIN PUBLIC KEY-----\nAA==\n-----END PUBLIC KEY-----"}
        const unreadable: ReceiptPublicKey = {kid: "k", alg: "Ed25519", publicKeyPem: "garbage"}
        const misnamed: ReceiptPublicKey = {...served(1), kid: "0000000000000000"}
        const repository = new PinnedReceiptsRepository(serving([other, unreadable, misnamed], [served(1)]), storage, SERVER)
        expect(await repository.keys()).toEqual([])
        expect(storage.items.has(RECEIPT_KEY_PINS)).toBe(false)
        expect(kids(await repository.keys())).toEqual([served(1).kid])
    })

    it("reads unreadable pins as a first view, and still answers when the keychain refuses to keep them", async () => {
        const storage = new Storage()
        storage.items.set(RECEIPT_KEY_PINS, "{")
        expect(kids(await new PinnedReceiptsRepository(serving([served(1)]), storage, SERVER).keys())).toEqual([served(1).kid])
        storage.items.set(RECEIPT_KEY_PINS, JSON.stringify({[SERVER]: "not a map"}))
        storage.refuseWrites = true
        expect(kids(await new PinnedReceiptsRepository(serving([served(2)]), storage, SERVER).keys())).toEqual([served(2).kid])
    })

    it("passes the receipts through", async () => {
        const inner = serving()
        const repository = new PinnedReceiptsRepository(inner, new Storage(), SERVER)
        await repository.list()
        await repository.get("r_1")
        expect(inner.list).toHaveBeenCalledTimes(1)
        expect(inner.get).toHaveBeenCalledWith("r_1")
    })
})
