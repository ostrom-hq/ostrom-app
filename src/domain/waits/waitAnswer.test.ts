// SPDX-License-Identifier: AGPL-3.0-only
import type {WaitAction, WaitField, WaitSpec} from "@/api/contract/waits"
import {
    actionsOf,
    blockedFields,
    checkAnswer,
    DEFAULT_MAX_TEXT,
    fieldDraft,
    fieldKind,
    initialDraft,
    initialField,
    isCalendarDate,
    MAX_OTHER,
} from "./waitAnswer"

const act = (id: string, requires?: string[]): WaitAction => ({id, label: id, ...(requires === undefined ? {} : {requires})})
const spec = (fields: WaitField[], actions: WaitAction[] = [act("go")]): WaitSpec => ({title: "Q", fields, actions})
const field = (type: string, extra: Partial<WaitField> = {}): WaitField => ({id: "f", type, label: "F", ...extra})

function values(question: WaitSpec, action: WaitAction, draft = initialDraft(question)): unknown {
    const checked = checkAnswer(question, action, draft)
    if (!checked.ok) throw new Error(`refused: ${JSON.stringify(checked.errors)}`)
    return checked.values
}

function errors(question: WaitSpec, action: WaitAction, draft = initialDraft(question)): unknown {
    const checked = checkAnswer(question, action, draft)
    if (checked.ok) throw new Error("accepted")
    return checked.errors
}

describe("fieldKind", () => {
    it.each(["text", "choice", "confirm", "number", "date", "info"])("knows %s", (type) => {
        expect(fieldKind(field(type))).toBe(type)
    })
    it.each(["file", "secret", "hologram"])("leaves %s to the console", (type) => {
        expect(fieldKind(field(type))).toBe("unsupported")
    })
})

describe("initialDraft", () => {
    it("starts from the default that fits the kind of each field", () => {
        const draft = initialDraft(
            spec([
                {...field("text"), id: "a", default: "hello"},
                {...field("text"), id: "b", default: 3},
                {...field("number"), id: "c", default: 60},
                {...field("number"), id: "d", default: "x"},
                {...field("number"), id: "e", default: Infinity},
                {...field("date"), id: "g", default: "2026-11-13"},
                {...field("confirm"), id: "h", default: true},
                {...field("confirm"), id: "i"},
                {...field("choice", {options: ["x", "y"], multiple: true}), id: "j", default: ["x", "z", 4]},
                {...field("choice", {options: ["x", "y"]}), id: "k", default: ["x", "y"]},
                {...field("choice", {options: ["x", "y"]}), id: "l", default: "y"},
                {...field("choice"), id: "m", default: "y"},
                {...field("info"), id: "n", default: "x"},
                {...field("file"), id: "o"},
            ]),
        )
        expect(draft["a"]?.text).toBe("hello")
        expect(draft["b"]?.text).toBe("")
        expect(draft["c"]?.text).toBe("60")
        expect(draft["d"]?.text).toBe("")
        expect(draft["e"]?.text).toBe("")
        expect(draft["g"]?.text).toBe("2026-11-13")
        expect(draft["h"]?.checked).toBe(true)
        expect(draft["i"]?.checked).toBe(false)
        expect(draft["j"]?.picked).toEqual(["x"])
        expect(draft["k"]?.picked).toEqual(["x"])
        expect(draft["l"]?.picked).toEqual(["y"])
        expect(draft["m"]?.picked).toEqual([])
        expect(draft["n"]?.text).toBe("")
        expect(draft["o"]?.text).toBe("")
    })

    it("has no field for a wait that asks none", () => {
        expect(initialDraft({title: "Q"})).toEqual({})
    })
})

describe("fieldDraft and actionsOf", () => {
    it("falls back on the initial state of a field the draft does not hold", () => {
        const text = {...field("text"), default: "hi"}
        expect(fieldDraft({}, text).text).toBe("hi")
        expect(fieldDraft({f: {...initialField(text), text: "typed"}}, text).text).toBe("typed")
    })

    it("gives a wait without action the one the server gives it", () => {
        expect(actionsOf({title: "Q"}, "Done")).toEqual([{id: "done", label: "Done"}])
        expect(actionsOf({title: "Q", actions: []}, "Done")).toEqual([{id: "done", label: "Done"}])
        expect(actionsOf({title: "Q", actions: [act("go")]}, "Done")).toEqual([act("go")])
    })
})

describe("isCalendarDate", () => {
    it.each(["2026-11-13", "2024-02-29", "2000-02-29", "0001-01-01", "2026-12-31"])("accepts %s", (text) => {
        expect(isCalendarDate(text)).toBe(true)
    })
    it.each(["2026-02-29", "1900-02-29", "2026-13-01", "2026-00-10", "2026-04-31", "2026-4-3", "13/11/2026", "2026-11-13T00:00", ""])(
        "refuses %s",
        (text) => {
            expect(isCalendarDate(text)).toBe(false)
        },
    )
})

describe("checkAnswer", () => {
    it("sends the typed value of each kind of field", () => {
        const question = spec([
            {...field("text"), id: "t"},
            {...field("number"), id: "n"},
            {...field("date"), id: "d"},
            {...field("confirm"), id: "c"},
            {...field("choice", {options: ["a", "b"]}), id: "o"},
            {...field("info"), id: "i"},
        ])
        const draft = {
            t: {text: "  hello  ", picked: [], other: "", checked: false},
            n: {text: " 4.5 ", picked: [], other: "", checked: false},
            d: {text: "2026-11-13", picked: [], other: "", checked: false},
            c: {text: "", picked: [], other: "", checked: true},
            o: {text: "", picked: ["b"], other: "", checked: false},
        }
        expect(values(question, act("go"), draft)).toEqual({t: "  hello  ", n: 4.5, d: "2026-11-13", c: true, o: "b"})
    })

    it("leaves an empty optional field out, and always sends a confirm", () => {
        const question = spec([{...field("text"), id: "t"}, {...field("number"), id: "n"}, {...field("date"), id: "d"}, {...field("confirm"), id: "c"}, {...field("choice", {options: ["a"]}), id: "o"}])
        expect(values(question, act("go"))).toEqual({c: false})
    })

    it("treats a blank text as empty", () => {
        const question = spec([field("text")])
        expect(values(question, act("go"), {f: {text: "   ", picked: [], other: "", checked: false}})).toEqual({})
    })

    it("asks for a required field and for the fields the action requires, not for the others", () => {
        const question = spec([{...field("text"), id: "a", required: true}, {...field("text"), id: "b"}, {...field("number"), id: "c"}], [act("send", ["b"]), act("skip")])
        const [send, skip] = question.actions ?? []
        if (send === undefined || skip === undefined) throw new Error("two actions")
        expect(errors(question, send)).toEqual({a: {kind: "required"}, b: {kind: "required"}})
        expect(errors(question, skip)).toEqual({a: {kind: "required"}})
    })

    it("never asks a value of an info field, even if an action names it", () => {
        const question = spec([{...field("info"), required: true}], [act("go", ["f", "ghost"])])
        expect(values(question, act("go", ["f", "ghost"]))).toEqual({})
    })

    it("refuses what is not a number or a calendar date", () => {
        const question = spec([{...field("number"), id: "n"}, {...field("date"), id: "d"}])
        const draft = {n: {text: "12abc", picked: [], other: "", checked: false}, d: {text: "2026-02-30", picked: [], other: "", checked: false}}
        expect(errors(question, act("go"), draft)).toEqual({n: {kind: "number"}, d: {kind: "date"}})
        expect(errors(spec([field("number")]), act("go"), {f: {text: "Infinity", picked: [], other: "", checked: false}})).toEqual({f: {kind: "number"}})
    })

    it("limits a text to its maxLength, or to 8,000 characters", () => {
        const short = spec([field("text", {maxLength: 3})])
        expect(values(short, act("go"), {f: {text: "abc", picked: [], other: "", checked: false}})).toEqual({f: "abc"})
        expect(errors(short, act("go"), {f: {text: "abcd", picked: [], other: "", checked: false}})).toEqual({f: {kind: "too-long", max: 3}})
        const long = spec([field("text")])
        expect(errors(long, act("go"), {f: {text: "x".repeat(DEFAULT_MAX_TEXT + 1), picked: [], other: "", checked: false}})).toEqual({f: {kind: "too-long", max: DEFAULT_MAX_TEXT}})
    })

    it("sends one option, or a list without repeats when the choice is multiple", () => {
        const one = spec([field("choice", {options: ["a", "b"]})])
        expect(values(one, act("go"), {f: {text: "", picked: ["a", "b"], other: "", checked: false}})).toEqual({f: "a"})
        const many = spec([field("choice", {options: ["a", "b"], multiple: true, allowOther: true})])
        expect(values(many, act("go"), {f: {text: "", picked: ["a"], other: " a ", checked: false}})).toEqual({f: ["a"]})
        expect(values(many, act("go"), {f: {text: "", picked: ["a", "b"], other: "c", checked: false}})).toEqual({f: ["a", "b", "c"]})
    })

    it("takes a free answer only when the choice allows it, up to 500 characters", () => {
        const closed = spec([field("choice", {options: ["a"]})])
        expect(values(closed, act("go"), {f: {text: "", picked: [], other: "zzz", checked: false}})).toEqual({})
        const open = spec([field("choice", {options: ["a"], allowOther: true})])
        expect(values(open, act("go"), {f: {text: "", picked: [], other: "x".repeat(MAX_OTHER), checked: false}})).toEqual({f: "x".repeat(MAX_OTHER)})
        expect(errors(open, act("go"), {f: {text: "", picked: [], other: "x".repeat(MAX_OTHER + 1), checked: false}})).toEqual({f: {kind: "too-long", max: MAX_OTHER}})
    })

    it("does not count a blank free answer as filled", () => {
        const open = spec([field("choice", {options: ["a"], allowOther: true, required: true})])
        expect(errors(open, act("go"), {f: {text: "", picked: [], other: "   ", checked: false}})).toEqual({f: {kind: "required"}})
    })

    it("falls back on the initial state of a field the draft misses", () => {
        expect(values(spec([field("number", {default: 7})]), act("go"), {})).toEqual({f: 7})
    })

    it("sends nothing for a field this app cannot fill", () => {
        const question = spec([field("file")])
        expect(values(question, act("go"))).toEqual({})
    })
})

describe("blockedFields", () => {
    const question = spec([{...field("file"), id: "doc", required: false}, {...field("secret"), id: "key"}, {...field("text"), id: "t"}], [act("plain"), act("with-key", ["key"]), act("with-doc", ["doc"])])
    it("names the fields the action needs that the app cannot fill", () => {
        expect(blockedFields(question, act("plain"))).toEqual([])
        expect(blockedFields(question, act("with-key", ["key"]))).toEqual(["key"])
        expect(blockedFields(question, act("with-doc", ["doc"]))).toEqual(["doc"])
    })
    it("names a required one for every action, and none for a wait without fields", () => {
        const required = spec([{...field("secret"), required: true}])
        expect(blockedFields(required, act("go"))).toEqual(["f"])
        expect(blockedFields({title: "Q"}, act("go"))).toEqual([])
    })
    it("lets a required unsupported field through checkAnswer: the buttons that need it are disabled instead", () => {
        const required = spec([{...field("secret"), required: true}])
        expect(values(required, act("go"))).toEqual({})
    })
})
