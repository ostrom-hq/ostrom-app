import type {SpendPeriod, SpendReport} from "@/api/contract/spending"

export interface SpendingRepository {
    report(period: SpendPeriod): Promise<SpendReport>
}
