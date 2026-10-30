import {fireEvent, render, screen} from "@testing-library/react-native"
import {TextField} from "./TextField"

describe("TextField", () => {
    it("types text, with a plain keyboard by default", async () => {
        const onChangeText = jest.fn()
        await render(<TextField label="Name" value="" onChangeText={onChangeText} />)
        const input = screen.getByLabelText("Name")
        expect(input).toHaveProp("keyboardType", "default")
        await fireEvent.changeText(input, "rev-b")
        expect(onChangeText).toHaveBeenCalledWith("rev-b")
    })

    it("shows its error under the field", async () => {
        await render(<TextField label="Server" value="x" onChangeText={jest.fn()} keyboardType="url" error="Not a server" />)
        expect(screen.getByLabelText("Server")).toHaveProp("keyboardType", "url")
        expect(screen.getByText("Not a server")).toBeOnTheScreen()
    })
})
