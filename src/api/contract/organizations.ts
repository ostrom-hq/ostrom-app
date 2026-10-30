// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"

/**
 * `GET /api/v1/me/organizations` (`listMyOrganizations`, core#31): the organizations the person is an active member
 * of, to choose the one sent in `Ostrom-Org`. It needs no `Ostrom-Org` itself and never answers `org_required`.
 *
 * Written by hand, not generated: the contract copy (`contract/CORE_SHA`) predates the route, and server#64 serves
 * `{items: [{id, slug, name, avatarUrl, role}]}`, without the `connectedVia` the contract's `Organization` requires.
 * The app reads only what both say. An empty `avatarUrl` (no linked GitHub organization) means no picture.
 */
export const MyOrganizationSchema = z.object({
    id: z.string().min(1),
    name: z.string().min(1),
    avatarUrl: z
        .string()
        .optional()
        .transform((url) => (url === undefined || url === "" ? undefined : url)),
})
export type MyOrganization = z.output<typeof MyOrganizationSchema>

export const MyOrganizationsSchema = z.object({items: z.array(MyOrganizationSchema)})
