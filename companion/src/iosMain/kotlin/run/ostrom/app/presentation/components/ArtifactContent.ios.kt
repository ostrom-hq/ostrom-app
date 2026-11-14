// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/ArtifactContent.ios.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import run.ostrom.app.domain.usecases.artifacts.OpenArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.isWebLink

actual val embedsArtifacts: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ArtifactContentView(
    contentUrl: String,
    prefix: String,
    html: String?,
    reload: Int,
    onExternal: (String) -> Unit,
    onMissing: (String) -> Unit,
    onCrashed: () -> Unit,
    modifier: Modifier,
) {
    val currentPrefix by rememberUpdatedState(prefix)
    val inlinePage by rememberUpdatedState(html != null)
    val external by rememberUpdatedState(onExternal)
    val missing by rememberUpdatedState(onMissing)
    val crashed by rememberUpdatedState(onCrashed)
    // Held here: the web view keeps its delegates weakly.
    val delegate = remember { ArtifactNavigation({ currentPrefix }, { inlinePage }, { external(it) }, { missing(it) }, { crashed() }) }
    // A new web view per URL and reload: a renewed ticket may have the same URL.
    key(contentUrl, html, reload) {
        UIKitView(
            factory = {
                val configuration = WKWebViewConfiguration().apply {
                    // Nothing kept between viewers: no cookie, no storage, no cache on disk.
                    websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                    allowsInlineMediaPlayback = true
                }
                WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                    navigationDelegate = delegate
                    UIDelegate = delegate
                    // The demo's page is held in memory: no base URL, so nothing it links to loads inside.
                    if (html != null) loadHTMLString(html, baseURL = null)
                    else NSURL.URLWithString(contentUrl)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
                }
            },
            modifier = modifier,
            onRelease = { view -> view.navigationDelegate = null; view.UIDelegate = null; view.stopLoading() },
        )
    }
}

/** Keeps the main frame under the ticket's prefix, sends pop-ups and user links outside, and reports a missing page. */
@OptIn(ExperimentalForeignApi::class)
private class ArtifactNavigation(
    private val prefix: () -> String,
    private val inlinePage: () -> Boolean,
    private val onExternal: (String) -> Unit,
    private val onMissing: (String) -> Unit,
    private val onCrashed: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val linkActivated = decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated
        if (url != null && OpenArtifactUseCase.allows(prefix(), url)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        // A frame of the page's own making (srcdoc, a blob or data document) stays inside its sandbox; the demo's page
        // held in memory loads as about:blank.
        val ownDocument = url == null || url.substringBefore(':').lowercase() in FRAME_SCHEMES
        if (ownDocument && (!mainFrame || (inlinePage() && !linkActivated))) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        if (url != null && mainFrame && linkActivated && isWebLink(url)) onExternal(url)
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
        val status = (decidePolicyForNavigationResponse.response as? NSHTTPURLResponse)?.statusCode
        val url = decidePolicyForNavigationResponse.response.URL?.absoluteString
        if (decidePolicyForNavigationResponse.forMainFrame && status == 404L && url != null) onMissing(url)
        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
    }

    /** The page's content process died: the screen reloads it once, in a new web view. */
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) = onCrashed()

    /** A pop-up never opens a second web view: a link the person followed is offered to the browser. */
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val url = forNavigationAction.request.URL?.absoluteString
        if (url != null && forNavigationAction.navigationType == WKNavigationTypeLinkActivated && isWebLink(url)) onExternal(url)
        return null
    }
}

private val FRAME_SCHEMES = setOf("about", "blob", "data")
