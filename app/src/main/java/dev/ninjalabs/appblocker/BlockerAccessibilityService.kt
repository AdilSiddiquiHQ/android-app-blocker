package dev.ninjalabs.appblocker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The enforcement layer.
 *
 * On every foreground change it asks [BlockerConfig] whether the new package
 * should be blocked, and if so covers it with [BlockOverlay]. It also handles
 * the three UI-level filters that are worth having in a blocker: browser
 * domains, Instagram Reels and YouTube Shorts.
 *
 * Nothing is cached here on purpose. The service is restarted by the system
 * unpredictably, and stale in-memory state is how "I turned it off but it still
 * blocks" bugs happen.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"

        val SOCIAL_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )

        val BROWSER_URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
            "com.opera.gx" to "com.opera.gx:id/url_field",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/url_edit_text",
            "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "org.mozilla.focus" to "org.mozilla.focus:id/display_url",
            "com.mi.globalbrowser" to "com.mi.globalbrowser:id/url_bar",
            "com.UCMobile.intl" to "com.UCMobile.intl:id/url_bar",
        )

        val INSTAGRAM_REELS_CONTAINERS = listOf(
            "com.instagram.android:id/clips_video_container",
        )

        val INSTAGRAM_SELECTED_TABS = listOf(
            "com.instagram.android:id/reels_tab",
            "com.instagram.android:id/clips_tab",
        )

        val YOUTUBE_SHORTS_CONTAINERS = listOf(
            "com.google.android.youtube:id/reel_player_view",
            "com.google.android.youtube:id/shorts_player_view",
            "com.google.android.youtube:id/reel_watch_fragment",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_viewer_page",
            "com.google.android.youtube:id/shorts_inner_container",
            "com.google.android.youtube:id/shorts_video_container",
        )

        val YOUTUBE_SHORTS_TABS = listOf(
            "com.google.android.youtube:id/shorts_tab",
            "com.google.android.youtube:id/tab_shorts",
        )

        val SITE_COOLDOWN_MS = 1_500L
        val SOCIAL_COOLDOWN_MS = 2_000L
    }

    private var lastSiteActionAt = 0L
    private var lastSocialActionAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { BlockOverlay.hide() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Nothing to cache: every decision reads BlockerConfig fresh.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        val packageName = current.packageName?.toString() ?: return

        // Our own overlay steals focus. Ignore it, otherwise it would hide itself.
        if (packageName == applicationContext.packageName) return

        if (current.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundChanged(packageName)
        }

        if (BlockOverlay.isShowing) return

        when {
            packageName == INSTAGRAM &&
                current.eventType in SOCIAL_EVENTS &&
                BlockerConfig.blockReels(this) -> blockInstagramReels()

            packageName == YOUTUBE &&
                current.eventType in SOCIAL_EVENTS &&
                BlockerConfig.blockShorts(this) -> blockYouTubeShorts()

            packageName in BROWSER_URL_BARS -> blockBrowserSite(packageName)
        }
    }

    private fun onForegroundChanged(packageName: String) {
        val reason = BlockerConfig.reasonToBlock(this, packageName)
        handler.removeCallbacks(hideOverlay)

        if (reason == null) {
            if (BlockOverlay.isShowing) BlockOverlay.hide()
            return
        }

        BlockOverlay.show(this, packageName, appLabel(packageName), reason)

        // A focus session or schedule ends on a timestamp; without this the
        // overlay would stay up until the next window change.
        val now = System.currentTimeMillis()
        val endsAt = when (reason) {
            BlockReason.Focus -> BlockerConfig.focusEndsAt(this)
            is BlockReason.Scheduled -> BlockerConfig.activeSchedule(this)?.endTimeMillis() ?: 0L
            BlockReason.Always -> 0L
        }
        if (endsAt > now) {
            handler.postDelayed(hideOverlay, endsAt - now)
        }
    }

    // ------------------------------------------------------------ websites

    private fun blockBrowserSite(packageName: String) {
        val sites = BlockerConfig.blockedSites(this)
        if (sites.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastSiteActionAt < SITE_COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        try {
            val reading = readUrlBar(root, BROWSER_URL_BARS[packageName]) ?: return
            if (reading.typing || reading.text.isEmpty()) return

            val host = normalizeHost(reading.text)
            if (host.isEmpty()) return

            val matched = sites.any { host == it || host.endsWith(".$it") }
            if (!matched) return

            lastSiteActionAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            toast(getString(R.string.toast_site_blocked))
        } finally {
            recycle(root)
        }
    }

    private fun normalizeHost(raw: String): String =
        raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .removePrefix("www.")

    private data class UrlReading(val text: String, val typing: Boolean)

    private fun readUrlBar(root: AccessibilityNodeInfo, preferredId: String?): UrlReading? {
        if (!preferredId.isNullOrEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(preferredId)
            if (!nodes.isNullOrEmpty()) {
                try {
                    val typing = nodes.any { it.isFocused }
                    val text = nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
                    if (!text.isNullOrBlank()) return UrlReading(text, typing)
                } finally {
                    nodes.forEach(::recycle)
                }
            }
        }

        val fallback = findUrlNode(root) ?: return null
        return try {
            UrlReading(fallback.text?.toString().orEmpty(), fallback.isFocused)
        } finally {
            recycle(fallback)
        }
    }

    private fun findUrlNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val looksLikeUrlBar = id.contains("url") ||
            id.contains("location_bar") ||
            id.contains("omnibox") ||
            id.contains("address")
        if (looksLikeUrlBar && !node.text.isNullOrBlank()) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = findUrlNode(child)
            if (result != null) return result
            recycle(child)
        }
        return null
    }

    // --------------------------------------------------------- social feeds

    private fun blockInstagramReels() {
        if (inCooldown()) return
        val root = rootInActiveWindow ?: return
        try {
            // Stories use a progress bar; they are not Reels.
            if (hasNode(root, listOf("com.instagram.android:id/reel_viewer_progress_bar"))) return

            val inReels = hasVisibleNode(root, INSTAGRAM_REELS_CONTAINERS) ||
                anySelected(root, INSTAGRAM_SELECTED_TABS)
            if (!inReels) return

            lastSocialActionAt = System.currentTimeMillis()

            if (anySelected(root, listOf("com.instagram.android:id/reels_tab")) &&
                clickFirstClickable(root, "com.instagram.android:id/feed_tab")
            ) {
                toast(getString(R.string.toast_reels_blocked))
                return
            }

            performGlobalAction(GLOBAL_ACTION_BACK)
            toast(getString(R.string.toast_reels_blocked))
        } finally {
            recycle(root)
        }
    }

    private fun blockYouTubeShorts() {
        if (inCooldown()) return
        val root = rootInActiveWindow ?: return
        try {
            val inShorts = anySelected(root, YOUTUBE_SHORTS_TABS) ||
                hasVisibleNode(root, YOUTUBE_SHORTS_CONTAINERS)
            if (!inShorts) return

            lastSocialActionAt = System.currentTimeMillis()

            if (anySelected(root, YOUTUBE_SHORTS_TABS) &&
                clickFirstClickable(root, "com.google.android.youtube:id/tab_home")
            ) {
                toast(getString(R.string.toast_shorts_blocked))
                return
            }

            performGlobalAction(GLOBAL_ACTION_BACK)
            toast(getString(R.string.toast_shorts_blocked))
        } finally {
            recycle(root)
        }
    }

    private fun inCooldown(): Boolean =
        System.currentTimeMillis() - lastSocialActionAt < SOCIAL_COOLDOWN_MS

    // ------------------------------------------------------------- helpers

    private fun anySelected(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            try {
                if (nodes.any { it.isSelected }) return true
            } finally {
                nodes.forEach(::recycle)
            }
        }
        return false
    }

    private fun hasVisibleNode(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            try {
                if (nodes.any { it.isVisibleToUser }) return true
            } finally {
                nodes.forEach(::recycle)
            }
        }
        return false
    }

    private fun hasNode(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach(::recycle)
                return true
            }
        }
        return false
    }

    private fun clickFirstClickable(root: AccessibilityNodeInfo, id: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: return false
        try {
            for (node in nodes) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null) {
                    if (candidate.isClickable &&
                        candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        return true
                    }
                    candidate = candidate.parent
                }
            }
        } finally {
            nodes.forEach(::recycle)
        }
        return false
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
            // Already recycled or the platform ignores it (API 33+).
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        BlockOverlay.hide()
        super.onDestroy()
    }
}
