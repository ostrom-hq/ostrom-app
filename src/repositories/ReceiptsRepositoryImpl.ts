import {
    type ReceiptEnvelope,
    ReceiptEnvelopeSchema,
    type ReceiptListItem,
    ReceiptListSchema,
    type ReceiptPublicKey,
    ReceiptPublicKeySchema,
} from "@/api/contract/receipts"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {ReceiptsRepository} from "./ReceiptsRepository"

export class ReceiptsRepositoryImpl implements ReceiptsRepository {
    constructor(private readonly http: HttpClient) {}

    list(): Promise<readonly ReceiptListItem[]> {
        return this.http.send({method: "GET", path: "/api/receipts", response: ReceiptListSchema})
    }

    get(id: string): Promise<ReceiptEnvelope> {
        return this.http.send({method: "GET", path: `/api/receipts/${segment(id)}`, response: ReceiptEnvelopeSchema})
    }

    publicKey(): Promise<ReceiptPublicKey> {
        return this.http.send({method: "GET", path: "/api/receipts/pubkey", response: ReceiptPublicKeySchema})
    }
}
