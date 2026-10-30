import type {TranslationShape} from "../types"

export const fr: TranslationShape = {
    app: {
        name: "Ostrom",
        tagline: "Les agents proposent. Les personnes décident.",
    },
    login: {
        server: "Serveur Ostrom",
        github: "Continuer avec GitHub",
        note: "Tu te connectes avec GitHub sur ton serveur Ostrom. Ton oui est signé à ton nom.",
        errors: {
            "invalid-url": "Saisis l'adresse de ton serveur Ostrom, en https://.",
            unexpected: "La connexion n'a pas pu aboutir sur ce téléphone. Réessaie.",
            "state-mismatch": "Cette connexion ne vient pas de cette app. Réessaie.",
            denied: "GitHub ou le serveur a refusé la connexion.",
            "exchange-failed": "Le serveur n'a pas accepté la connexion. Vérifie l'adresse et réessaie.",
        },
    },
    home: {
        title: "En attente de toi",
        empty: "Rien n'attend ton oui pour l'instant.",
    },
}
