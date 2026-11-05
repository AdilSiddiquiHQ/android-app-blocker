package dev.ninjalabs.appblocker

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var usageStatus: TextView
    private lateinit var usageButton: Button

    private lateinit var focusStatus: TextView
    private lateinit var focusButton: Button

    private lateinit var blockedAppsSummary: TextView
    private lateinit var sitesSummary: TextView
    private lateinit var schedulesSummary: TextView

    private lateinit var reelsSwitch: SwitchCompat
    private lateinit var shortsSwitch: SwitchCompat
    private lateinit var watchdogSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accessibilityStatus = findViewById(R.id.permAccessibilityStatus)
        accessibilityButton = findViewById(R.id.permAccessibilityButton)
        overlayStatus = findViewById(R.id.permOverlayStatus)
        overlayButton = findViewById(R.id.permOverlayButton)
        usageStatus = findViewById(R.id.permUsageStatus)
        usageButton = findViewById(R.id.permUsageButton)

        focusStatus = findViewById(R.id.focusStatus)
        focusButton = findViewById(R.id.focusButton)

        blockedAppsSummary = findViewById(R.id.blockedAppsSummary)
        sitesSummary = findViewById(R.id.sitesSummary)
        schedulesSummary = findViewById(R.id.schedulesSummary)

        reelsSwitch = findViewById(R.id.blockReelsSwitch)
        shortsSwitch = findViewById(R.id.blockShortsSwitch)
        watchdogSwitch = findViewById(R.id.watchdogSwitch)

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        usageButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        focusButton.setOnClickListener { toggleFocus() }

        findViewById<Button>(R.id.chooseAppsButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.editSitesButton).setOnClickListener { editSites() }
        findViewById<Button>(R.id.manageSchedulesButton).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }

        bindSwitch(reelsSwitch) { BlockerConfig.setBlockReels(this, it) }
        bindSwitch(shortsSwitch) { BlockerConfig.setBlockShorts(this, it) }
        bindSwitch(watchdogSwitch) { setWatchdog(it) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11y = isAccessibilityEnabled()
        accessibilityStatus.text = getString(if (a11y) R.string.perm_enabled else R.string.perm_disabled)
        accessibilityButton.isEnabled = !a11y
        accessibilityButton.alpha = if (a11y) 0.4f else 1f

        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(if (overlay) R.string.perm_enabled else R.string.perm_disabled)
        overlayButton.isEnabled = !overlay
        overlayButton.alpha = if (overlay) 0.4f else 1f

        val usage = UsageStats.hasPermission(this)
        usageStatus.text = getString(if (usage) R.string.perm_enabled else R.string.perm_disabled)
        usageButton.isEnabled = !usage
        usageButton.alpha = if (usage) 0.4f else 1f

        val focusEndsAt = BlockerConfig.focusEndsAt(this)
        if (focusEndsAt > System.currentTimeMillis()) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(focusEndsAt))
            focusStatus.text = getString(R.string.focus_running, time)
            focusButton.text = getString(R.string.focus_stop)
            focusButton.setBackgroundResource(R.drawable.bg_button_secondary)
        } else {
            focusStatus.text = getString(R.string.focus_idle)
            focusButton.text = getString(R.string.focus_start)
            focusButton.setBackgroundResource(R.drawable.bg_button_primary)
        }

        val blocked = BlockerConfig.blockedPackages(this)
        blockedAppsSummary.text = if (blocked.isEmpty()) {
            getString(R.string.blocked_apps_none)
        } else {
            getString(R.string.blocked_apps_count, blocked.size)
        }

        val sites = BlockerConfig.blockedSites(this)
        sitesSummary.text = if (sites.isEmpty()) {
            getString(R.string.sites_none)
        } else {
            getString(R.string.sites_count, sites.size) + " · " + sites.take(3).joinToString(", ")
        }

        val schedules = BlockerConfig.schedules(this)
        val active = BlockerConfig.activeSchedule(this)
        schedulesSummary.text = when {
            schedules.isEmpty() -> getString(R.string.schedules_none)
            active != null -> "${active.label} · ending ${minuteLabel(active.endMinute)}"
            else -> getString(R.string.schedules_count, schedules.size)
        }

        setSwitchSilently(reelsSwitch, BlockerConfig.blockReels(this))
        setSwitchSilently(shortsSwitch, BlockerConfig.blockShorts(this))

        val watchdogConfigured = BlockerConfig.watchdogEnabled(this)
        if (watchdogConfigured && !usage) {
            BlockerConfig.setWatchdogEnabled(this, false)
            BlockMonitorService.stop(this)
            setSwitchSilently(watchdogSwitch, false)
        } else {
            setSwitchSilently(watchdogSwitch, watchdogConfigured)
        }
    }

    private fun toggleFocus() {
        if (BlockerConfig.isFocusActive(this)) {
            BlockerConfig.stopFocus(this)
        } else {
            BlockerConfig.startFocus(this, 25)
        }
        refresh()
    }

    private fun setWatchdog(enabled: Boolean) {
        if (!enabled) {
            BlockerConfig.setWatchdogEnabled(this, false)
            BlockMonitorService.stop(this)
            return
        }

        if (!UsageStats.hasPermission(this)) {
            setSwitchSilently(watchdogSwitch, false)
            toast("Grant usage access first, then enable the watchdog.")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        BlockerConfig.setWatchdogEnabled(this, true)
        BlockMonitorService.start(this)
    }

    private fun editSites() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            setText(BlockerConfig.blockedSites(this@MainActivity).joinToString("\n"))
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.sites_dialog_title)
            .setMessage(R.string.sites_dialog_hint)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                BlockerConfig.setBlockedSites(
                    this,
                    input.text.toString().split('\n', ','),
                )
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BlockerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            it.equals(expected.flattenToString(), ignoreCase = true) ||
                it.equals(expected.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun minuteLabel(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    private fun bindSwitch(view: SwitchCompat, onChange: (Boolean) -> Unit) {
        view.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun setSwitchSilently(view: SwitchCompat, checked: Boolean) {
        if (view.isChecked == checked) return
        view.setOnCheckedChangeListener(null)
        view.isChecked = checked
        // Listener is restored by the next refresh/bind call.
        bindSwitch(view) {
            when (view.id) {
                R.id.blockReelsSwitch -> BlockerConfig.setBlockReels(this, it)
                R.id.blockShortsSwitch -> BlockerConfig.setBlockShorts(this, it)
                R.id.watchdogSwitch -> setWatchdog(it)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("The watchdog will run without a visible notification.")
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 10
    }
}
