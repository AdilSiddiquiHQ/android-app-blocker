package dev.ninjalabs.appblocker

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Multi-select list of launchable apps. Saves straight into [BlockerConfig]. */
class AppPickerActivity : AppCompatActivity() {

    private data class Entry(val packageName: String, val label: String)

    private val allApps = ArrayList<Entry>()
    private val visibleApps = ArrayList<Entry>()
    private val selected = HashSet<String>()
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var adapter: AppAdapter
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        selected.addAll(BlockerConfig.blockedPackages(this))

        val listView = findViewById<ListView>(R.id.appList)
        adapter = AppAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = visibleApps[position]
            if (!selected.add(entry.packageName)) selected.remove(entry.packageName)
            adapter.notifyDataSetChanged()
        }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            BlockerConfig.setBlockedPackages(this, selected)
            finish()
        }

        loadApps()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            val loaded = ArrayList<Entry>()

            for (info in resolved) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                loaded.add(Entry(pkg, info.loadLabel(pm).toString()))
            }

            loaded.sortBy { it.label.lowercase() }
            runOnUiThread {
                allApps.clear()
                allApps.addAll(loaded)
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        visibleApps.clear()
        if (query.isEmpty()) {
            visibleApps.addAll(allApps)
        } else {
            allApps.filterTo(visibleApps) {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun iconFor(packageName: String): Drawable? =
        iconCache.getOrPut(packageName) {
            try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                packageManager.defaultActivityIcon
            }
        }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleApps.size
        override fun getItem(position: Int): Any = visibleApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = visibleApps[position]

            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(iconFor(entry.packageName))
            view.findViewById<TextView>(R.id.appLabel).text = entry.label
            view.findViewById<TextView>(R.id.appPackage).text = entry.packageName
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = selected.contains(entry.packageName)

            return view
        }
    }
}
