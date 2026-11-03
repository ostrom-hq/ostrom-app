// SPDX-License-Identifier: AGPL-3.0-only
import {type UseQueryResult, useQuery} from "@tanstack/react-query"
import type {Account} from "@/api/contract/account"
import type {MyOrganization} from "@/api/contract/organizations"
import {useRepositories} from "@/services/AppServices"

export const accountKeys = {
    all: ["account"] as const,
    organizations: () => [...accountKeys.all, "organizations"] as const,
    profile: () => [...accountKeys.all, "profile"] as const,
}

/** The organizations the person may act in. */
export function useMyOrganizations(): UseQueryResult<readonly MyOrganization[]> {
    const {account} = useRepositories()
    return useQuery({queryKey: accountKeys.organizations(), queryFn: () => account.organizations()})
}

/** The person's account and how they sign in: theirs, in no organization. */
export function useMyAccount(): UseQueryResult<Account> {
    const {profile} = useRepositories()
    return useQuery({queryKey: accountKeys.profile(), queryFn: () => profile.account()})
}
