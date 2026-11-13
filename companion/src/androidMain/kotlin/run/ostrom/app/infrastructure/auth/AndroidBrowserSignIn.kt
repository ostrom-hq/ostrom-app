// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.infrastructure.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import run.ostrom.app.domain.services.BrowserSignIn

/**
 * GitHub sign-in in a Custom Tab (the system's browser, its cookies and password manager; the app never sees the page).
 * The server sends the browser back to `ostrom://auth/callback`, which the activity's intent filter catches and hands
 * to [AuthRedirects]; a tab closed without it ends the attempt with null.
 */
class AndroidBrowserSignIn(private val context: Context) : BrowserSignIn {
    override suspend fun authenticate(url: String, callbackScheme: String): String? {
        val redirect = AuthRedirects.begin(callbackScheme)
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, Uri.parse(url))
            redirect.await()
        } finally {
            // Cancelled (the screen left) or failed to open: no later redirect is taken for this attempt.
            AuthRedirects.forget(redirect)
        }
    }
}

/** The one sign-in waiting for its redirect, fed by the activity (its intents, and its return to the foreground). */
object AuthRedirects {
    private var scheme: String? = null
    private var pending: CompletableDeferred<String?>? = null
    private var left = false

    @Synchronized
    internal fun begin(callbackScheme: String): CompletableDeferred<String?> {
        // A newer attempt replaces the one before: only the latest is answered.
        pending?.complete(null)
        return CompletableDeferred<String?>().also {
            pending = it
            scheme = callbackScheme
            left = false
        }
    }

    @Synchronized
    internal fun forget(attempt: CompletableDeferred<String?>) {
        if (pending !== attempt) return
        pending = null
        left = false
    }

    /** A link opened the app: the redirect of the waiting sign-in when it has its scheme. True when it was taken. */
    @Synchronized
    fun deliver(url: String): Boolean {
        val waiting = pending ?: return false
        if (!url.startsWith("${scheme}://")) return false
        waiting.complete(url)
        pending = null
        return true
    }

    /** The app went to the background (the tab opened over it). */
    @Synchronized
    fun onPause() {
        if (pending != null) left = true
    }

    /** The app is back without a redirect: the person closed the tab. */
    @Synchronized
    fun onResume() {
        if (!left) return
        pending?.complete(null)
        pending = null
        left = false
    }
}
