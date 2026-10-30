import type {ReceiptEnvelope, ReceiptPage, ReceiptPublicKey} from "@/api/contract/receipts"

/** Signed receipts, kept raw for verification on the phone. */
export interface ReceiptsRepository {
    list(): Promise<ReceiptPage>
    get(id: string): Promise<ReceiptEnvelope>
    /** The public keys of the instance; a receipt names the one that signed it. */
    keys(): Promise<readonly ReceiptPublicKey[]>
}
