// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/LocalTime.android.kt; see NOTICE.
package run.ostrom.app.presentation.screens

actual fun deviceUtcOffsetMinutes(epochMillis: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMillis) / 60_000
