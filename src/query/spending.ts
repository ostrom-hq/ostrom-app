import {type UseQueryResult, useQuery} from "@tanstack/react-query"
import type {SpendPeriod, SpendReport} from "@/api/contract/spending"
import {useRepositories} from "@/services/AppServices"

export const spendingKeys = {report: (period: SpendPeriod) => ["spending", period] as const}

export function useSpending(period: SpendPeriod): UseQueryResult<SpendReport> {
    const {spending} = useRepositories()
    return useQuery({queryKey: spendingKeys.report(period), queryFn: () => spending.report(period)})
}
