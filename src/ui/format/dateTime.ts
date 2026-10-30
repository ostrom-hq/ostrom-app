/** A date and time in the person's language, short (`30 Oct, 09:00`). */
export function formatDateTime(iso: string, locale: string): string {
    return new Intl.DateTimeFormat(locale, {day: "numeric", month: "short", hour: "2-digit", minute: "2-digit"}).format(new Date(iso))
}
