package com.arjun.gander

import android.util.AndroidRuntimeException
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWebView

/** A phone whose WebView is missing, turned off or part way through an update: making one throws. */
@Implements(WebView::class)
class ShadowMissingWebView : ShadowWebView() {
    @Implementation
    override fun ensureProviderCreated() {
        throw AndroidRuntimeException("Failed to load WebView provider: No WebView installed")
    }
}

/**
 * What the WebView says when Android has taken its renderer away for memory. Only the WebView is
 * meant to make one, hence the deprecated constructor, and a test standing in for it has no other.
 */
@Suppress("DEPRECATION")
internal fun rendererReclaimed(): RenderProcessGoneDetail = object : RenderProcessGoneDetail() {
    override fun didCrash() = false
    override fun rendererPriorityAtExit() = 0
}
