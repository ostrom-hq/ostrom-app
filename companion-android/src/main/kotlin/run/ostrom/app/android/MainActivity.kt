// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion-android MainActivity.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import run.ostrom.app.App
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.push.NotificationLinks
import run.ostrom.app.infrastructure.auth.AuthRedirects
import run.ostrom.app.infrastructure.biometrics.ForegroundActivity
import run.ostrom.app.presentation.navigation.IncomingLinks

/** A FragmentActivity: BiometricPrompt shows over it before an answer. */
class MainActivity : FragmentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ForegroundActivity.attach(this)
        if (savedInstanceState == null) handle(intent)
        setContent { App() }
        askForNotifications()
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

    /**
     * Android 13+ shows pushes only with the person's permission: asked once, when an account is first signed in (a
     * prompt before any sign-in would not say what for). Refused, it stays off until the person turns it on in Settings.
     */
    private fun askForNotifications() {
        if (!PushNotifications.configured || Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(PUSH_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(ASKED, false)) return
        val accounts = GlobalContext.get().get<AccountRepository>()
        lifecycleScope.launch {
            accounts.accounts.first { list -> list.any { it.signedIn } }
            if (preferences.getBoolean(ASKED, false)) return@launch
            preferences.edit().putBoolean(ASKED, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** A tapped notification's link, or the sign-in's redirect (`ostrom://auth/callback?…`) for the sign-in waiting for it. */
    private fun handle(intent: Intent?) {
        // Relaunched from Recents, the activity gets its first intent again: that link was already used.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action != Intent.ACTION_VIEW) return
        val link = intent.dataString?.take(MAX_LINK) ?: return
        if (link.startsWith(NotificationLinks.SCHEME, ignoreCase = true)) IncomingLinks.open(link) else AuthRedirects.deliver(link)
    }

    private companion object {
        const val MAX_LINK = 4_096

        /** Whether the notification permission was asked (not a secret). */
        const val PUSH_PREFERENCES = "push"
        const val ASKED = "asked"
    }
}
