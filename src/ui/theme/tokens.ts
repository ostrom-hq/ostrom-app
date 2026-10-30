/**
 * Ostrom "Signal" primitives, copied from the brand tokens (research/brand/tokens/tokens.css).
 * Rule of the brand: garnet means "a human is awaited or has just decided", and nothing else.
 * Amber is budget, red is error.
 */
export const palette = {
    garnet50: "#F8EEF1",
    garnet100: "#EFDFE3",
    garnet200: "#E2BFC8",
    garnet300: "#F390A9",
    garnet400: "#C7304A",
    garnet450: "#D6455E",
    garnet500: "#8E1B3A",
    garnet600: "#74152F",
    garnet700: "#5C1025",
    garnet900: "#4A1D26",
    ink0: "#FFFFFF",
    ink25: "#FBFAFA",
    ink50: "#F7F5F5",
    ink100: "#EEE9EA",
    ink200: "#E5DCDE",
    ink300: "#CDC2C5",
    ink400: "#A3979A",
    ink500: "#776B6E",
    ink600: "#665A5D",
    ink700: "#44393C",
    ink800: "#35292D",
    ink850: "#1E1619",
    ink900: "#1A1214",
    ink950: "#0F0A0C",
    inkNight: "#140E10",
    green50: "#E7F4EC",
    green400: "#4CC27F",
    green600: "#1B7F45",
    green900: "#12301F",
    amber50: "#FBF1DF",
    amber400: "#E3A33B",
    amber600: "#B7791F",
    amber800: "#8A5A12",
    amber900: "#33240C",
    red50: "#FBE6E3",
    red400: "#FF6B5A",
    red600: "#C8352A",
    red900: "#3A1714",
    blue50: "#E6EEFB",
    blue400: "#7AA7FF",
    blue600: "#2B62C9",
    blue900: "#14213A",
} as const

export const space = {1: 4, 2: 8, 3: 12, 4: 16, 5: 24, 6: 32, 7: 48, 8: 64} as const

export const radius = {xs: 2, sm: 4, md: 8, lg: 12, xl: 16, pill: 999} as const

export const fontSize = {12: 12, 14: 14, 16: 16, 20: 20, 28: 28, 40: 40} as const

export const duration = {fast: 120, base: 160, slow: 280, open: 820, close: 420} as const
