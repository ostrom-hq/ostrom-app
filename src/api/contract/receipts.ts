import {z} from "zod"
import {
    zCheckAttestation,
    zListReceiptKeysResponse,
    zListReceiptsResponse,
    zPublicKey,
    zReceipt,
    zReceiptNode,
} from "@/api/gen/zod.gen"
import {orElse} from "./orElse"

const CheckAttestationSchema = orElse(zCheckAttestation, "unknown")
/**
 * Where a check run of a receipt comes from: the workstation's word, an Ostrom runner, or the CI; `unknown` for a value
 * a newer server sends, which this app cannot vouch for and never counts as proof.
 */
export type CheckAttestation = z.output<typeof CheckAttestationSchema>

/** A receipt for the screen: an origin this app does not know is read as `unknown`, never as an unreadable receipt. */
const ReceiptViewSchema = zReceipt.extend({
    nodes: z.array(zReceiptNode.extend({attestation: CheckAttestationSchema.optional()})).optional(),
})

export type ReceiptView = z.output<typeof ReceiptViewSchema>

/** A receipt as received: the untouched object, for the signature, and its typed view, for the screen. */
export interface ReceiptEnvelope {
    readonly readable: true
    readonly raw: unknown
    readonly view: ReceiptView
}

/** A receipt the app cannot display (a newer shape): kept, so the list still shows it and can still verify it. */
export interface UnreadableReceipt {
    readonly readable: false
    readonly raw: unknown
    readonly detail: string
}

export type ReceiptListItem = ReceiptEnvelope | UnreadableReceipt

/** Display only: verification works on the raw object, since the schema drops the fields it does not know. */
function readReceipt(raw: unknown): ReceiptListItem {
    const view = ReceiptViewSchema.safeParse(raw)
    return view.success ? {readable: true, raw, view: view.data} : {readable: false, raw, detail: z.prettifyError(view.error)}
}

/** `GET /api/v1/receipts/{id}`: one receipt, which must be readable. */
export const ReceiptEnvelopeSchema = z.unknown().transform((raw, context): ReceiptEnvelope => {
    const item = readReceipt(raw)
    if (item.readable) return item
    context.issues.push({code: "custom", message: item.detail, input: raw})
    return z.NEVER
})

/** `GET /api/v1/receipts`: one unreadable receipt never hides the others. */
export const ReceiptPageSchema = zListReceiptsResponse
    .extend({items: z.array(z.unknown())})
    .transform((page) => ({items: page.items.map(readReceipt), next: page.next}))
export type ReceiptPage = z.output<typeof ReceiptPageSchema>

/**
 * How a new receipt key is handed over: the id of the key it replaces, and that key's Ed25519 signature (base64) over
 * the new key (`keyRotationMessage`). An unreadable one is dropped, never the key.
 */
const KeyRotationSchema = z.object({kid: z.string().min(1), signature: z.string().min(1)})

/** `GET /api/v1/receipts/keys`: the public keys of the instance that sign receipts. */
export const ReceiptKeysSchema = zListReceiptKeysResponse.extend({
    keys: z.array(zPublicKey.extend({rotation: KeyRotationSchema.catch(undefined).optional()})),
})
export type ReceiptPublicKey = z.output<typeof ReceiptKeysSchema>["keys"][number]
