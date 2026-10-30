import type {ReceiptEnvelope, ReceiptListItem, ReceiptPublicKey} from "@/api/contract/receipts"

/** Signed receipts, kept raw for verification on the phone. */
export interface ReceiptsRepository {
    list(): Promise<readonly ReceiptListItem[]>
    get(id: string): Promise<ReceiptEnvelope>
    publicKey(): Promise<ReceiptPublicKey>
}
