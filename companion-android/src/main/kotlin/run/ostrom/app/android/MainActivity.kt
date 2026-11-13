// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion-android MainActivity.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import run.ostrom.app.App
import run.ostrom.app.infrastructure.auth.AuthRedirects
import run.ostrom.app.infrastructure.biometrics.ForegroundActivity

/** A FragmentActivity: BiometricPrompt shows over it before an answer. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ForegroundActivity.attach(this)
        if (savedInstanceState == null) handle(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        ForegroundActivity.detach(this)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        AuthRedirects.onPause()
    }

    override fun onResume() {
        super.onResume()
        AuthRedirects.onResume()
    }

    /** The sign-in's redirect (`ostrom://auth/callback?…`), handed to the sign-in waiting for it. */
    private fun handle(intent: Intent?) {
        // Relaunched from Recents, the activity gets its first intent again: that redirect was already used.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.take(MAX_LINK)?.let(AuthRedirects::deliver)
    }

    private companion object {
        const val MAX_LINK = 4_096
    }
}
