package io.github.ksen0v.appguard

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.WorkerThread
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Выполнение shell-команд с правами ADB (uid shell) через Shizuku — без root.
 * Shell может видеть все процессы (`ps -A`) и делать `am force-stop`.
 */
object ShizukuShell {

    const val PACKAGE = "moe.shizuku.privileged.api"

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    data class ExecResult(val exitCode: Int, val output: String)

    fun status(context: Context): Status {
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        if (!alive) {
            return if (AppInfo.isInstalled(context, PACKAGE)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
        return try {
            when {
                Shizuku.isPreV11() -> Status.NOT_RUNNING
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
                else -> Status.NO_PERMISSION
            }
        } catch (t: Throwable) {
            Status.NOT_RUNNING
        }
    }

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // В API 13 newProcess закрыт, но по-прежнему работает; это самый короткий путь к shell.
    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    @WorkerThread
    fun exec(command: String): ExecResult {
        val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ExecResult(exitCode, output)
    }

    /** Имена всех процессов в системе (у приложения это пакет или «пакет:процесс»). */
    @WorkerThread
    fun runningProcessNames(): Set<String> {
        var output = exec("ps -A -o NAME").output
        if (output.lines().size < 5) output = exec("ps").output
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast(' ') }
            .toSet()
    }
}
