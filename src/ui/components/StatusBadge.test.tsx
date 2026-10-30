// SPDX-License-Identifier: AGPL-3.0-only
import {render, screen} from "@testing-library/react-native"
import i18next from "i18next"
import {StatusBadge} from "./StatusBadge"

type Status = Parameters<typeof StatusBadge>[0]["status"]

const STATUSES: readonly Status[] = ["pending", "approved", "rejected", "expired"]

describe("StatusBadge", () => {
    it.each(STATUSES)("names a %s request", async (status) => {
        await render(<StatusBadge status={status} />)
        expect(screen.getByText(i18next.t(`status.${status}`))).toBeOnTheScreen()
    })
})
