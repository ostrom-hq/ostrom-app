import type {ReceiptEnvelope, ReceiptPublicKey} from "@/api/contract/receipts"

/** Signed receipts, kept raw for verification on the phone. */
export interface ReceiptsRepository {
    list(): Promise<readonly ReceiptEnvelope[]>
    get(id: string): Promise<ReceiptEnvelope>
    publicKey(): Promise<ReceiptPublicKey>
}
