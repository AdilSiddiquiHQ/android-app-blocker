package dev.ninjalabs.appblocker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Full-screen "this app is blocked" window.
 *
 * The overlay is deliberately dumb: it covers the app, explains why, and
 * offers two exits. It never tries to kill the app underneath, which would
 * require root or a device-owner profile.
 */
object BlockOverlay {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var currentPackage: String? = null

    var isShowing: Boolean = false
        private set

    fun show(context: Context, packageName: String, appLabel: String, reason: BlockReason) {
        if (!Settings.canDrawOverlays(context)) return
        if (isShowing && currentPackage == packageName) return
        if (isShowing) hide()

        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.CENTER }

        val view = buildView(app, packageName, appLabel, reason)

        try {
            manager.addView(view, params)
            windowManager = manager
            rootView = view
            currentPackage = packageName
            isShowing = true
        } catch (_: Exception) {
            isShowing = false
            currentPackage = null
            rootView = null
        }
    }

    fun hide() {
        val manager = windowManager
        val view = rootView
        if (manager != null && view != null) {
            try {
                manager.removeView(view)
            } catch (_: Exception) {
                // Already detached.
            }
        }
        rootView = null
        windowManager = null
        currentPackage = null
        isShowing = false
    }

    private fun buildView(
        context: Context,
        packageName: String,
        appLabel: String,
        reason: BlockReason,
    ): View {
        fun dp(value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).roundToInt()

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F20B0C0F"))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        container.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        val iconFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1B1F27"))
                setStroke(dp(1), Color.parseColor("#2A3040"))
            }
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER }
            try {
                setImageDrawable(context.packageManager.getApplicationIcon(packageName))
            } catch (_: Exception) {
                setImageResource(android.R.drawable.ic_lock_lock)
            }
        }
        iconFrame.addView(icon)
        column.addView(iconFrame)

        val title = TextView(context).apply {
            text = when (reason) {
                BlockReason.Always -> context.getString(R.string.block_title_always, appLabel)
                BlockReason.Focus -> context.getString(R.string.block_title_focus, appLabel)
                is BlockReason.Scheduled -> context.getString(R.string.block_title_schedule, appLabel)
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        column.addView(title)

        val body = TextView(context).apply {
            text = when (reason) {
                BlockReason.Always -> context.getString(R.string.block_body_always)
                BlockReason.Focus -> context.getString(R.string.block_body_focus)
                is BlockReason.Scheduled -> context.getString(R.string.block_body_schedule) +
                    "\n\n" + reason.label
            }
            setTextColor(Color.parseColor("#98A2B3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(8), dp(10), dp(8), 0)
        }
        column.addView(body)

        val home = Button(context).apply {
            text = context.getString(R.string.block_action_home)
            isAllCaps = false
            setTextColor(Color.parseColor("#0B0C0F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7C5CFF"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { goHome(context) }
        }
        column.addView(
            home,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        val open = Button(context).apply {
            text = context.getString(R.string.block_action_open)
            isAllCaps = false
            setTextColor(Color.parseColor("#F2F4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#2A3040"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener {
                hide()
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            }
        }
        column.addView(
            open,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.isForceDarkAllowed = false
        }

        return container
    }

    private fun goHome(context: Context) {
        hide()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
