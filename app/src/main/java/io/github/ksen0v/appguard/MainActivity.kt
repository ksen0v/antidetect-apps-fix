package io.github.ksen0v.appguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var slotA: Slot
    private lateinit var slotB: Slot
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private lateinit var accessibilityStatus: TextView
    private lateinit var interceptSwitch: MaterialSwitch
    private lateinit var modeText: TextView

    private val pickA = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPicked(it, isA = true)
    }
    private val pickB = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPicked(it, isA = false)
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = Config(this)

        slotA = Slot(findViewById(R.id.slot_a), isA = true)
        slotB = Slot(findViewById(R.id.slot_b), isA = false)

        shizukuStatus = findViewById(R.id.shizuku_status)
        shizukuButton = findViewById(R.id.shizuku_button)
        shizukuButton.setOnClickListener { onShizukuButton() }

        accessibilityStatus = findViewById(R.id.accessibility_status)
        findViewById<Button>(R.id.accessibility_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        interceptSwitch = findViewById(R.id.intercept_switch)
        interceptSwitch.setOnCheckedChangeListener { _, checked -> config.interceptEnabled = checked }

        modeText = findViewById(R.id.mode_text)

        findViewById<Button>(R.id.stop_both_button).setOnClickListener { stopBoth(it) }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        slotA.bind(config.packageA)
        slotB.bind(config.packageB)

        val shizuku = ShizukuShell.status(this)
        shizukuStatus.setText(
            when (shizuku) {
                ShizukuShell.Status.NOT_INSTALLED -> R.string.shizuku_not_installed
                ShizukuShell.Status.NOT_RUNNING -> R.string.shizuku_not_running
                ShizukuShell.Status.NO_PERMISSION -> R.string.shizuku_no_permission
                ShizukuShell.Status.READY -> R.string.shizuku_ready
            },
        )
        shizukuButton.visibility = if (shizuku == ShizukuShell.Status.READY) View.GONE else View.VISIBLE
        shizukuButton.setText(
            when (shizuku) {
                ShizukuShell.Status.NOT_INSTALLED -> R.string.shizuku_install
                ShizukuShell.Status.NO_PERMISSION -> R.string.shizuku_grant
                else -> R.string.shizuku_open
            },
        )

        val accessibility = GuardAccessibilityService.isEnabled(this)
        accessibilityStatus.setText(if (accessibility) R.string.accessibility_on else R.string.accessibility_off)

        interceptSwitch.isChecked = config.interceptEnabled

        modeText.setText(
            when {
                shizuku == ShizukuShell.Status.READY -> R.string.mode_shizuku
                accessibility -> R.string.mode_accessibility
                else -> R.string.mode_none
            },
        )
    }

    private fun onShizukuButton() {
        when (ShizukuShell.status(this)) {
            ShizukuShell.Status.NOT_INSTALLED ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))

            ShizukuShell.Status.NOT_RUNNING ->
                packageManager.getLaunchIntentForPackage(ShizukuShell.PACKAGE)?.let { startActivity(it) }

            ShizukuShell.Status.NO_PERMISSION -> Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)

            ShizukuShell.Status.READY -> Unit
        }
    }

    private fun onPicked(result: ActivityResult, isA: Boolean) {
        val pkg = result.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        val other = if (isA) config.packageB else config.packageA
        if (pkg == other) {
            Toast.makeText(this, R.string.same_app_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (isA) config.packageA = pkg else config.packageB = pkg
        refresh()
    }

    private fun stopBoth(button: View) {
        button.isEnabled = false
        lifecycleScope.launch {
            val ok = Guard.stopBoth(this@MainActivity)
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.stop_both_done else R.string.stop_both_failed,
                Toast.LENGTH_SHORT,
            ).show()
            button.isEnabled = true
        }
    }

    private inner class Slot(root: View, private val isA: Boolean) {
        private val icon: ImageView = root.findViewById(R.id.slot_icon)
        private val name: TextView = root.findViewById(R.id.slot_name)
        private val pkgText: TextView = root.findViewById(R.id.slot_package)
        private val launch: Button = root.findViewById(R.id.slot_launch)
        private val shortcut: Button = root.findViewById(R.id.slot_shortcut)
        private var pkg: String? = null

        init {
            root.findViewById<TextView>(R.id.slot_title).setText(if (isA) R.string.slot_a else R.string.slot_b)
            root.findViewById<Button>(R.id.slot_pick).setOnClickListener {
                (if (isA) pickA else pickB).launch(Intent(this@MainActivity, AppPickerActivity::class.java))
            }
            launch.setOnClickListener {
                pkg?.let {
                    startActivity(
                        Intent(this@MainActivity, LaunchActivity::class.java)
                            .putExtra(LaunchActivity.EXTRA_PACKAGE, it),
                    )
                }
            }
            shortcut.setOnClickListener { pkg?.let { Shortcuts.requestPin(this@MainActivity, it) } }
        }

        fun bind(pkg: String?) {
            this.pkg = pkg
            if (pkg == null) {
                icon.setImageResource(R.drawable.ic_app_placeholder)
                name.setText(R.string.not_selected)
                pkgText.text = ""
            } else {
                val drawable = AppInfo.icon(this@MainActivity, pkg)
                if (drawable != null) icon.setImageDrawable(drawable) else icon.setImageResource(R.drawable.ic_app_placeholder)
                name.text = AppInfo.label(this@MainActivity, pkg)
                pkgText.text = pkg
            }
            launch.isEnabled = pkg != null
            shortcut.isEnabled = pkg != null
        }
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 1001
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}
