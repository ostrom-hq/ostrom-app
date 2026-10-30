/** English is the source language: every other locale must have exactly these keys (checked by the compiler). */
export const en = {
    app: {
        name: "Ostrom",
        tagline: "Agents propose. People decide.",
    },
    login: {
        server: "Ostrom server",
        github: "Continue with GitHub",
        note: "You sign in with GitHub on your Ostrom server. Your yes is signed in your name.",
        errors: {
            "invalid-url": "Enter the address of your Ostrom server, starting with https://.",
            unexpected: "The sign-in could not finish on this phone. Try again.",
            "state-mismatch": "This sign-in did not come from this app. Try again.",
            denied: "GitHub or the server refused the sign-in.",
            "exchange-failed": "The server did not accept the sign-in. Check the address and try again.",
        },
    },
    home: {
        title: "Waiting for you",
        empty: "Nothing waits for your yes right now.",
    },
} as const
