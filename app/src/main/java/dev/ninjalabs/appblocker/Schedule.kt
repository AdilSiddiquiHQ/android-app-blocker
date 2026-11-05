package dev.ninjalabs.appblocker

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * A recurring block window.
 *
 * [days] uses ISO numbering: 1 = Monday ... 7 = Sunday. An empty set means
 * "every day". Windows may cross midnight (for example 21:00 -> 04:00).
 */
data class Schedule(
    val id: String,
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<Int>,
    val enabled: Boolean = true,
) {
    fun isActiveAt(calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled || startMinute == endMinute) return false

        val now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val today = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        val yesterday = if (today == 1) 7 else today - 1

        fun matchesDay(day: Int) = days.isEmpty() || days.contains(day)

        return when {
            startMinute < endMinute ->
                now in startMinute until endMinute && matchesDay(today)

            // Cross-midnight window: the evening half belongs to today, the
            // morning half belongs to the day the window started.
            now >= startMinute -> matchesDay(today)
            now < endMinute -> matchesDay(yesterday)
            else -> false
        }
    }

    fun timeLabel(): String =
        String.format(Locale.US, "%02d:%02d - %02d:%02d", startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)

    fun daysLabel(): String {
        if (days.isEmpty()) return "Every day"
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return days.sorted().joinToString(", ") { names[it - 1] }
    }

    /** Epoch millis at which the currently active window ends. */
    fun endTimeMillis(now: Calendar = Calendar.getInstance()): Long {
        val end = now.clone() as Calendar
        end.set(Calendar.HOUR_OF_DAY, endMinute / 60)
        end.set(Calendar.MINUTE, endMinute % 60)
        end.set(Calendar.SECOND, 0)
        end.set(Calendar.MILLISECOND, 0)
        if (end.timeInMillis <= now.timeInMillis) {
            end.add(Calendar.DAY_OF_MONTH, 1)
        }
        return end.timeInMillis
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("start", startMinute)
        put("end", endMinute)
        put("enabled", enabled)
        put("days", JSONArray(days.sorted()))
    }

    companion object {
        fun fromJson(json: JSONObject): Schedule {
            val days = mutableSetOf<Int>()
            val array = json.optJSONArray("days")
            if (array != null) {
                for (i in 0 until array.length()) days.add(array.optInt(i))
            }
            return Schedule(
                id = json.optString("id"),
                label = json.optString("label", "Focus"),
                startMinute = json.optInt("start", 9 * 60),
                endMinute = json.optInt("end", 17 * 60),
                days = days,
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}
