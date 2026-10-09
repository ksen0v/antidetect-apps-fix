package io.github.ksen0v.appguard

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Основная логика: перед запуском одного приложения пары убедиться, что второе не работает,
 * при необходимости принудительно его закрыть и только потом открыть первое.
 */
object Guard {

    private const val TAG = "AppGuard"
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_.]+$")

    /** Живёт дольше activity: запуск не должен прерываться, если экран ушёл в фон. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutex = Mutex()

    /** Идёт операция — служба спецвозможностей в это время не перехватывает переходы. */
    @Volatile
    var busy = false
        private set

    sealed interface Result {
        data object Launched : Result
        data class Failed(val message: String) : Result
    }

    suspend fun isRunning(context: Context, pkg: String): Boolean {
        if (!ShizukuShell.isReady()) return Config(context).isPossiblyRunning(pkg)
        return withContext(Dispatchers.IO) {
            ShizukuShell.runningProcessNames().any { it == pkg || it.startsWith("$pkg:") }
        }
    }

    /** Принудительно останавливает [pkg]. true — приложение точно остановлено. */
    suspend fun forceStop(context: Context, pkg: String): Boolean {
        require(PACKAGE_NAME.matches(pkg)) { "Bad package name: $pkg" }
        val stopped = if (ShizukuShell.isReady()) {
            withContext(Dispatchers.IO) { ShizukuShell.exec("am force-stop $pkg") }
            waitUntilStopped(context, pkg)
        } else {
            GuardAccessibilityService.instance?.forceStopViaSettings(pkg) ?: false
        }
        if (stopped) Config(context).setPossiblyRunning(pkg, false)
        Log.i(TAG, "forceStop($pkg) -> $stopped")
        return stopped
    }

    private suspend fun waitUntilStopped(context: Context, pkg: String): Boolean {
        repeat(20) {
            if (!isRunning(context, pkg)) return true
            delay(150)
        }
        return false
    }

    /**
     * Открывает [target], предварительно закрыв второе приложение пары, если оно работает.
     * [restartTarget] — закрыть и сам [target] (он успел запуститься, пока второе работало).
     */
    suspend fun launchExclusive(context: Context, target: String, restartTarget: Boolean = false): Result =
        mutex.withLock {
            busy = true
            try {
                doLaunch(context, target, restartTarget)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "launchExclusive($target) failed", e)
                Result.Failed(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }

    private suspend fun doLaunch(context: Context, target: String, restartTarget: Boolean): Result {
        val app = context.applicationContext
        val config = Config(app)
        val launchIntent = app.packageManager.getLaunchIntentForPackage(target)
            ?: return Result.Failed(app.getString(R.string.error_no_launch_intent, target))

        val other = config.counterpartOf(target)
        if (other != null && isRunning(app, other) && !forceStop(app, other)) {
            return Result.Failed(app.getString(R.string.error_stop_failed, AppInfo.label(app, other)))
        }
        if (restartTarget && !forceStop(app, target)) {
            return Result.Failed(app.getString(R.string.error_stop_failed, AppInfo.label(app, target)))
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Служба спецвозможностей может стартовать activity из фона; activity — только пока она на экране.
        val starter: Context = GuardAccessibilityService.instance ?: context
        starter.startActivity(launchIntent)
        config.setPossiblyRunning(target, true)
        return Result.Launched
    }

    /** Останавливает оба приложения пары. */
    suspend fun stopBoth(context: Context): Boolean = mutex.withLock {
        busy = true
        try {
            val config = Config(context)
            listOfNotNull(config.packageA, config.packageB)
                .map { forceStop(context, it) }
                .all { it }
        } finally {
            busy = false
        }
    }
}
