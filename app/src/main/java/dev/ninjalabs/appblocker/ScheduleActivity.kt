package dev.ninjalabs.appblocker

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.UUID

/**
 * Schedules reuse the same block list as the rest of the app: when a window is
 * active, every blocked package is covered during that window.
 */
class ScheduleActivity : AppCompatActivity() {

    private val schedules = ArrayList<Schedule>()
    private lateinit var adapter: ScheduleAdapter
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        schedules.addAll(BlockerConfig.schedules(this))

        emptyHint = findViewById(R.id.emptyHint)
        val listView = findViewById<ListView>(R.id.scheduleList)
        adapter = ScheduleAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> showEditor(schedules[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(schedules[position])
            true
        }

        findViewById<Button>(R.id.addScheduleButton).setOnClickListener { showEditor(null) }

        updateEmptyState()
    }

    private fun showEditor(existing: Schedule?) {
        val view = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val labelInput = view.findViewById<EditText>(R.id.labelInput)
        val startButton = view.findViewById<Button>(R.id.startButton)
        val endButton = view.findViewById<Button>(R.id.endButton)
        val daysContainer = view.findViewById<LinearLayout>(R.id.daysContainer)

        var startMinute = existing?.startMinute ?: 9 * 60
        var endMinute = existing?.endMinute ?: 17 * 60
        val days = existing?.days?.toMutableSet() ?: mutableSetOf(1, 2, 3, 4, 5)

        labelInput.setText(existing?.label.orEmpty())

        fun updateTimeLabels() {
            startButton.text = "${getString(R.string.schedule_start)}  ${minuteLabel(startMinute)}"
            endButton.text = "${getString(R.string.schedule_end)}  ${minuteLabel(endMinute)}"
        }
        updateTimeLabels()

        startButton.setOnClickListener {
            pickTime(startMinute) { startMinute = it; updateTimeLabels() }
        }
        endButton.setOnClickListener {
            pickTime(endMinute) { endMinute = it; updateTimeLabels() }
        }

        val letters = listOf("M", "T", "W", "T", "F", "S", "S")
        for (day in 1..7) {
            val box = CheckBox(this).apply {
                text = letters[day - 1]
                isChecked = days.contains(day)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) days.add(day) else days.remove(day)
                }
            }
            daysContainer.addView(box)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.schedule_add else R.string.schedule_edit)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.schedule_delete) { _, _ -> confirmDelete(existing) }
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (startMinute == endMinute) {
                    toast("Start and end time cannot be the same.")
                    return@setOnClickListener
                }

                val schedule = Schedule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = labelInput.text.toString().trim().ifBlank { "Focus" },
                    startMinute = startMinute,
                    endMinute = endMinute,
                    days = days.toSet(),
                    enabled = true,
                )

                val index = schedules.indexOfFirst { it.id == schedule.id }
                if (index >= 0) schedules[index] = schedule else schedules.add(schedule)

                BlockerConfig.setSchedules(this, schedules)
                adapter.notifyDataSetChanged()
                updateEmptyState()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun confirmDelete(schedule: Schedule) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"${schedule.label}\"?")
            .setPositiveButton(R.string.schedule_delete) { _, _ ->
                schedules.removeAll { it.id == schedule.id }
                BlockerConfig.setSchedules(this, schedules)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickTime(initialMinute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            initialMinute / 60,
            initialMinute % 60,
            true,
        ).show()
    }

    private fun updateEmptyState() {
        emptyHint.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun minuteLabel(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class ScheduleAdapter : BaseAdapter() {
        override fun getCount(): Int = schedules.size
        override fun getItem(position: Int): Any = schedules[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_schedule, parent, false)
            val schedule = schedules[position]

            view.findViewById<TextView>(R.id.scheduleLabel).text = schedule.label
            view.findViewById<TextView>(R.id.scheduleTime).text = schedule.timeLabel()
            view.findViewById<TextView>(R.id.scheduleDays).text = schedule.daysLabel()
            view.findViewById<TextView>(R.id.scheduleEnabled).text =
                if (schedule.enabled) "ON" else "OFF"

            return view
        }
    }
}
