package dev.ninjalabs.appblocker

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.util.Calendar

/** Why an app is currently being blocked. */
sealed class BlockReason(val key: String) {
    data object Always : BlockReason("always")
    data object Focus : BlockReason("focus")
    data class Scheduled(val label: String) : BlockReason("schedule")
}

/**
 * Single source of truth for blocker configuration.
 *
 * Everything lives in SharedPreferences so the accessibility service, the
 * watchdog and the UI always read the same state. The UI writes; the services
 * read on every event. No in-memory caching, no stale companion objects.
 */
object BlockerConfig {

    private const val PREFS = "app_blocker_prefs"

    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_BLOCKED_SITES = "blocked_sites"
    private const val KEY_FOCUS_ENDS_AT = "focus_ends_at"
    private const val KEY_BLOCK_REELS = "block_reels"
    private const val KEY_BLOCK_SHORTS = "block_shorts"
    private const val KEY_WATCHDOG = "watchdog_enabled"
    private const val KEY_SCHEDULES = "schedules"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- apps

    fun blockedPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_BLOCKED_PACKAGES, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun setBlockedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_BLOCKED_PACKAGES, JSONArray(packages.toList()).toString())
            .apply()
    }

    // ------------------------------------------------------------- websites

    fun blockedSites(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_BLOCKED_SITES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<String>(array.length())
            for (i in 0 until array.length()) result.add(array.getString(i))
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setBlockedSites(context: Context, sites: List<String>) {
        val cleaned = sites.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        prefs(context).edit()
            .putString(KEY_BLOCKED_SITES, JSONArray(cleaned).toString())
            .apply()
    }

    // -------------------------------------------------------- focus session

    fun focusEndsAt(context: Context): Long = prefs(context).getLong(KEY_FOCUS_ENDS_AT, 0L)

    fun isFocusActive(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        focusEndsAt(context) > now

    fun startFocus(context: Context, minutes: Int = 25) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit().putLong(KEY_FOCUS_ENDS_AT, end).apply()
    }

    fun stopFocus(context: Context) {
        prefs(context).edit().putLong(KEY_FOCUS_ENDS_AT, 0L).apply()
    }

    // ---------------------------------------------------------- feature flags

    fun blockReels(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCK_REELS, true)
    fun setBlockReels(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_REELS, enabled).apply()

    fun blockShorts(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCK_SHORTS, true)
    fun setBlockShorts(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_SHORTS, enabled).apply()

    fun watchdogEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WATCHDOG, false)
    fun setWatchdogEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_WATCHDOG, enabled).apply()

    // ------------------------------------------------------------- schedules

    fun schedules(context: Context): List<Schedule> {
        val raw = prefs(context).getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(Schedule.fromJson(array.getJSONObject(i))) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setSchedules(context: Context, schedules: List<Schedule>) {
        val array = JSONArray()
        schedules.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_SCHEDULES, array.toString()).apply()
    }

    // ------------------------------------------------------------- decision

    /**
     * Returns the reason [packageName] should be blocked right now, or null.
     * Reads fresh state on every call; the accessibility service calls this on
     * each foreground change.
     */
    fun reasonToBlock(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ): BlockReason? {
        if (packageName == context.packageName) return null
        if (isSystemPackage(context, packageName)) return null
        if (packageName !in blockedPackages(context)) return null

        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        schedules(context)
            .firstOrNull { it.isActiveAt(calendar) }
            ?.let { return BlockReason.Scheduled(it.label) }

        if (isFocusActive(context, now)) return BlockReason.Focus

        return BlockReason.Always
    }

    fun activeSchedule(context: Context, now: Long = System.currentTimeMillis()): Schedule? {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        return schedules(context).firstOrNull { it.isActiveAt(calendar) }
    }

    private fun isSystemPackage(context: Context, packageName: String): Boolean {
        if (packageName == "com.android.systemui" || packageName == "android") return true
        val home = homePackage(context)
        return packageName == home
    }

    private var cachedHome: String? = null

    private fun homePackage(context: Context): String? {
        cachedHome?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName?.also { cachedHome = it }
    }
}
