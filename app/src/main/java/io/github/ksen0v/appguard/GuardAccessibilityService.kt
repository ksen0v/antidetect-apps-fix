package io.github.ksen0v.appguard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1. Перехват: замечает, что приложение пары открыли обычной иконкой, пока второе работает,
 *    сворачивает его, закрывает оба и открывает нужное заново.
 * 2. Запасной способ закрытия без Shizuku: открывает «О приложении» и нажимает «Остановить».
 */
class GuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var config: Config

    /** Окна этих пакетов не считаются сменой приложения (шторка, клавиатура, сам AppGuard). */
    private var transientPackages: Set<String> = emptySet()
    private var currentForeground: String? = null

    private var pending: StopRequest? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (pending == null) return
            processPending()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = Config(this)
        transientPackages = buildTransientPackages()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(pollRunnable)
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (pending != null) {
            processPending()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (Guard.busy || pkg in transientPackages || pkg == currentForeground) return

        currentForeground = pkg
        if (config.isGuarded(pkg)) onGuardedAppForeground(pkg)
    }

    private fun onGuardedAppForeground(pkg: String) {
        scope.launch {
            val other = config.counterpartOf(pkg)
            if (!config.interceptEnabled || other == null || !Guard.isRunning(this@GuardAccessibilityService, other)) {
                config.setPossiblyRunning(pkg, true)
                return@launch
            }

            performGlobalAction(GLOBAL_ACTION_HOME)
            toast(
                getString(
                    R.string.toast_intercepted,
                    AppInfo.label(this@GuardAccessibilityService, other),
                    AppInfo.label(this@GuardAccessibilityService, pkg),
                ),
            )
            val result = Guard.launchExclusive(this@GuardAccessibilityService, pkg, restartTarget = true)
            if (result is Guard.Result.Failed) toast(result.message)
        }
    }

    // --- Закрытие через экран «О приложении» ---

    private enum class Stage { FIND_BUTTON, CONFIRM, VERIFY }

    private class StopRequest(val settingsPackages: Set<String>) {
        val result = CompletableDeferred<Boolean>()
        var stage = Stage.FIND_BUTTON
            private set
        var stageStartedAt = SystemClock.uptimeMillis()
            private set

        fun moveTo(next: Stage) {
            stage = next
            stageStartedAt = SystemClock.uptimeMillis()
        }
    }

    suspend fun forceStopViaSettings(pkg: String): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        val settingsPackages = buildSet {
            add(SETTINGS_PACKAGE)
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.let { add(it) }
        }
        val request = StopRequest(settingsPackages)
        pending = request
        return try {
            startActivity(intent)
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            withTimeoutOrNull(STOP_TIMEOUT_MS) { request.result.await() } ?: false
        } finally {
            pending = null
            handler.removeCallbacks(pollRunnable)
            if (rootInActiveWindow?.packageName?.toString() in settingsPackages) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun processPending() {
        val request = pending ?: return
        if (request.result.isCompleted) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in request.settingsPackages) return
        val elapsed = SystemClock.uptimeMillis() - request.stageStartedAt

        when (request.stage) {
            Stage.FIND_BUTTON -> {
                val button = findForceStopButton(root) ?: return
                if (!button.isEnabled) {
                    // Кнопка неактивна — приложение уже остановлено.
                    request.result.complete(true)
                } else if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    request.moveTo(Stage.CONFIRM)
                }
            }

            Stage.CONFIRM -> {
                val confirm = findConfirmButton(root)
                if (confirm != null) {
                    if (confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) request.moveTo(Stage.VERIFY)
                } else if (elapsed > CONFIRM_WAIT_MS) {
                    // На некоторых прошивках подтверждения нет.
                    request.moveTo(Stage.VERIFY)
                }
            }

            Stage.VERIFY -> {
                val button = findForceStopButton(root)
                if (button != null && !button.isEnabled) {
                    request.result.complete(true)
                } else if (elapsed > VERIFY_WAIT_MS) {
                    // Кнопку не нашли (экран сменился) — считаем, что остановка прошла; если она всё ещё
                    // активна — приложение снова поднялось, честно сообщаем о неудаче.
                    request.result.complete(button == null)
                }
            }
        }
    }

    private val forceStopLabels: List<String> by lazy {
        val labels = mutableListOf<String>()
        try {
            // Точная локализованная подпись кнопки из ресурсов «Настроек».
            val res = packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = res.getIdentifier("force_stop", "string", SETTINGS_PACKAGE)
            if (id != 0) labels += res.getString(id)
        } catch (e: Exception) {
            // Нет доступа к ресурсам — остаются стандартные подписи.
        }
        labels += FALLBACK_FORCE_STOP_LABELS
        labels.map { it.trim() }.distinct()
    }

    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in FORCE_STOP_IDS) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return clickableOf(it) ?: it }
        }
        val node = findNode(root) { node ->
            val text = (node.text ?: node.contentDescription)?.toString()?.trim()
            text != null && forceStopLabels.any { it.equals(text, ignoreCase = true) }
        } ?: return null
        return clickableOf(node) ?: node
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.let { return it }
        val node = findNode(root) { node ->
            val text = node.text?.toString()?.trim()
            text != null && CONFIRM_LABELS.any { it.equals(text, ignoreCase = true) }
        } ?: return null
        return clickableOf(node)
    }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    private fun buildTransientPackages(): Set<String> {
        val set = mutableSetOf(packageName, "com.android.systemui", "android")
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { set += it.packageName }
        return set
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val POLL_INTERVAL_MS = 250L
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val CONFIRM_WAIT_MS = 1_500L
        private const val VERIFY_WAIT_MS = 3_000L

        private val FORCE_STOP_IDS = listOf("com.android.settings:id/force_stop_button")
        private val FALLBACK_FORCE_STOP_LABELS = listOf(
            "Остановить", "Принудительно остановить", "Принудительная остановка", "Закрыть", "Завершить",
            "Force stop", "Stop",
        )
        private val CONFIRM_LABELS = listOf("OK", "ОК", "Да", "Yes")

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, GuardAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
