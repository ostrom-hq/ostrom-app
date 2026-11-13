// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import run.ostrom.app.domain.services.BrowserSignIn
import kotlin.coroutines.resume

/**
 * GitHub sign-in in an `ASWebAuthenticationSession`: the system's browser sheet, which shares Safari's cookies and
 * password manager and hands back the `ostrom://auth/callback` redirect; the app never sees the page. A sheet the
 * person closes ends the attempt with null.
 */
class IosBrowserSignIn : BrowserSignIn {
    // The session holds its provider weakly: kept here for as long as the app runs.
    private val anchor = PresentationAnchor()

    override suspend fun authenticate(url: String, callbackScheme: String): String? = withContext(Dispatchers.Main) {
        val page = NSURL.URLWithString(url) ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            val session = ASWebAuthenticationSession(uRL = page, callbackURLScheme = callbackScheme) { callback, _ ->
                if (continuation.isActive) continuation.resume(callback?.absoluteString)
            }
            session.presentationContextProvider = anchor
            if (!session.start() && continuation.isActive) continuation.resume(null)
            continuation.invokeOnCancellation { session.cancel() }
        }
    }
}

private class PresentationAnchor : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    @Suppress("DEPRECATION") // keyWindow: the app has one scene and one window.
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
        UIApplication.sharedApplication.keyWindow ?: UIWindow()
}
