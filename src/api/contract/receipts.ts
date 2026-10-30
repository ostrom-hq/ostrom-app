import {z} from "zod"
import {DateTimeSchema} from "./common"

export const ReceiptDecisionSchema = z.object({
    approvalId: z.string(),
    by: z.string(),
    at: DateTimeSchema,
    result: z.string(),
    selfApproved: z.boolean().nullish(),
})

/**
 * The fields of a receipt the app displays (prototype `Receipt`, shared/src/types.ts).
 * Display only: verification works on the raw object, since this schema drops fields it does not know.
 */
export const ReceiptViewSchema = z.object({
    id: z.string(),
    subject: z.object({kind: z.string(), id: z.string(), digest: z.string()}),
    initiator: z.string(),
    agent: z.string().nullish(),
    model: z.string().nullish(),
    rules: z.array(z.object({id: z.string(), digest: z.string()})),
    decisions: z.array(ReceiptDecisionSchema),
    costUsd: z.number().nullish(),
    createdAt: DateTimeSchema,
    signature: z.string(),
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

function readReceipt(raw: unknown): ReceiptListItem {
    const view = ReceiptViewSchema.safeParse(raw)
    return view.success ? {readable: true, raw, view: view.data} : {readable: false, raw, detail: z.prettifyError(view.error)}
}

/** One receipt, which must be readable. */
export const ReceiptEnvelopeSchema = z.unknown().transform((raw, context): ReceiptEnvelope => {
    const item = readReceipt(raw)
    if (item.readable) return item
    context.issues.push({code: "custom", message: item.detail, input: raw})
    return z.NEVER
})

/** A list of receipts: one unreadable receipt never hides the others. */
export const ReceiptListSchema = z.array(z.unknown()).transform((items) => items.map(readReceipt))

/** `GET /api/receipts/pubkey`: the instance key that signs receipts. */
export const ReceiptPublicKeySchema = z.object({
    kid: z.string(),
    alg: z.literal("ed25519"),
    publicKeyPem: z.string(),
})
export type ReceiptPublicKey = z.output<typeof ReceiptPublicKeySchema>
