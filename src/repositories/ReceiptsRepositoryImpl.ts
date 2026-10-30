import {
    type ReceiptEnvelope,
    ReceiptEnvelopeSchema,
    ReceiptKeysSchema,
    type ReceiptPage,
    ReceiptPageSchema,
    type ReceiptPublicKey,
} from "@/api/contract/receipts"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {ReceiptsRepository} from "./ReceiptsRepository"

export class ReceiptsRepositoryImpl implements ReceiptsRepository {
    constructor(private readonly http: HttpClient) {}

    list(): Promise<ReceiptPage> {
        return this.http.send({method: "GET", path: "/api/v1/receipts", response: ReceiptPageSchema})
    }

    get(id: string): Promise<ReceiptEnvelope> {
        return this.http.send({method: "GET", path: `/api/v1/receipts/${segment(id)}`, response: ReceiptEnvelopeSchema})
    }

    async keys(): Promise<readonly ReceiptPublicKey[]> {
        const {keys} = await this.http.send({method: "GET", path: "/api/v1/receipts/keys", response: ReceiptKeysSchema})
        return keys
    }
}
