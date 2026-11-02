// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import {zListMyOrganizationsResponse, zOrganization} from "@/api/gen/zod.gen"

/**
 * `GET /api/v1/me/organizations` (`listMyOrganizations`): the organizations the person is an active member of, to
 * choose the one sent in `Ostrom-Org`. It needs no `Ostrom-Org` itself and never answers `org_required`.
 *
 * The contract's `Organization`, of which the app reads only the id, the name and the picture: server#64 serves
 * `{id, slug, name, avatarUrl, role}`, without the `connectedVia` the contract requires, and the chooser needs no more.
 * An empty `avatarUrl` (no linked GitHub organization) means no picture.
 */
export const MyOrganizationSchema = z.object({
    id: zOrganization.shape.id.min(1),
    name: zOrganization.shape.name.min(1),
    avatarUrl: zOrganization.shape.avatarUrl.optional().transform((url) => (url === undefined || url === "" ? undefined : url)),
})
export type MyOrganization = z.output<typeof MyOrganizationSchema>

export const MyOrganizationsSchema = zListMyOrganizationsResponse.extend({items: z.array(MyOrganizationSchema)})
