// SPDX-License-Identifier: AGPL-3.0-only
import {keepPreviousData, type UseQueryResult, useQuery} from "@tanstack/react-query"
import type {SpendPeriod, SpendReport} from "@/api/contract/spending"
import {useRepositories} from "@/services/AppServices"

export const spendingKeys = {report: (period: SpendPeriod) => ["spending", period] as const}

export function useSpending(period: SpendPeriod): UseQueryResult<SpendReport> {
    const {spending} = useRepositories()
    // The other period keeps showing while the new one loads: no spinner, no lost scroll, on each switch.
    return useQuery({queryKey: spendingKeys.report(period), queryFn: () => spending.report(period), placeholderData: keepPreviousData})
}
