// SPDX-License-Identifier: AGPL-3.0-only
import {fireEvent, render, screen} from "@testing-library/react-native"
import {Button, type ButtonVariant} from "./Button"

const VARIANTS: readonly ButtonVariant[] = ["signal", "neutral", "danger"]

describe("Button", () => {
    it.each(VARIANTS)("presses a %s button", async (variant) => {
        const onPress = jest.fn()
        await render(<Button label="Approve" onPress={onPress} variant={variant} />)
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(onPress).toHaveBeenCalledTimes(1)
    })

    it("is neutral by default", async () => {
        await render(<Button label="Later" onPress={jest.fn()} />)
        expect(screen.getByRole("button", {name: "Later"})).toBeEnabled()
    })

    it("does nothing while disabled or busy, and shows a spinner when busy", async () => {
        const onPress = jest.fn()
        await render(
            <>
                <Button label="Off" onPress={onPress} disabled />
                <Button label="Working" onPress={onPress} busy testID="busy" />
            </>,
        )
        expect(screen.getByRole("button", {name: "Off"})).toBeDisabled()
        expect(screen.getByTestId("busy")).toBeDisabled()
        expect(screen.queryByText("Working")).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Off"}))
        expect(onPress).not.toHaveBeenCalled()
    })
})
