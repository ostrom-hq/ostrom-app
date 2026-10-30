/** An amount in US dollars, as agents' providers bill it (`$0.13`, `$1,204.50`). */
export function formatUsd(usd: number, locale: string): string {
    return new Intl.NumberFormat(locale, {style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2}).format(usd)
}
