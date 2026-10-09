package io.github.ksen0v.appguard

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Список установленных приложений с иконкой в лаунчере; возвращает выбранный пакет. */
class AppPickerActivity : AppCompatActivity() {

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    private val adapter = AppAdapter { entry ->
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, entry.packageName))
        finish()
    }
    private var allApps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        setTitle(R.string.picker_title)

        val list = findViewById<RecyclerView>(R.id.app_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val search = findViewById<EditText>(R.id.search)
        search.doAfterTextChanged { filter(it?.toString().orEmpty()) }

        val progress = findViewById<View>(R.id.progress)
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { loadApps() }
            progress.visibility = View.GONE
            filter(search.text?.toString().orEmpty())
        }
    }

    private fun loadApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { AppEntry(it, AppInfo.label(this, it), AppInfo.icon(this, it)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun filter(query: String) {
        val q = query.trim()
        adapter.submit(
            if (q.isEmpty()) allApps
            else allApps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) },
        )
    }

    private class AppAdapter(private val onClick: (AppEntry) -> Unit) : RecyclerView.Adapter<AppAdapter.Holder>() {

        private var items: List<AppEntry> = emptyList()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val pkg: TextView = view.findViewById(R.id.app_package)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.icon.setImageDrawable(entry.icon)
            holder.label.text = entry.label
            holder.pkg.text = entry.packageName
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "io.github.ksen0v.appguard.extra.PICKED_PACKAGE"
    }
}
