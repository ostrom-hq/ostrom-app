// SPDX-License-Identifier: AGPL-3.0-only
import type {Edge} from "react-native-safe-area-context"

/** A tab screen has no header and sits above the tab bar, which keeps the bottom inset: only the top one is its own. */
export const TAB_EDGES: readonly Edge[] = ["top"]
