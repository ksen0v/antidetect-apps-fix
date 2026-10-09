package io.github.ksen0v.appguard

import android.content.Context
import androidx.core.content.edit

/** Выбранная пара приложений и состояние, которое нужно помнить между запусками. */
class Config(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    var packageA: String?
        get() = prefs.getString(KEY_A, null)
        set(value) = prefs.edit { putString(KEY_A, value) }

    var packageB: String?
        get() = prefs.getString(KEY_B, null)
        set(value) = prefs.edit { putString(KEY_B, value) }

    var interceptEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTERCEPT, true)
        set(value) = prefs.edit { putBoolean(KEY_INTERCEPT, value) }

    fun isGuarded(pkg: String): Boolean = pkg == packageA || pkg == packageB

    /** Второе приложение пары или null, если [pkg] не входит в пару (или пара не заполнена). */
    fun counterpartOf(pkg: String): String? {
        val a = packageA
        val b = packageB
        return when (pkg) {
            a -> b
            b -> a
            else -> null
        }
    }

    /**
     * Без Shizuku чужие процессы не видны, поэтому запоминаем сами: приложение «может работать»,
     * если оно появлялось на экране после того, как мы его последний раз остановили.
     * По умолчанию считаем, что может (безопаснее лишний раз проверить).
     */
    fun isPossiblyRunning(pkg: String): Boolean = prefs.getBoolean(KEY_RUNNING_PREFIX + pkg, true)

    fun setPossiblyRunning(pkg: String, running: Boolean) =
        prefs.edit { putBoolean(KEY_RUNNING_PREFIX + pkg, running) }

    private companion object {
        const val KEY_A = "package_a"
        const val KEY_B = "package_b"
        const val KEY_INTERCEPT = "intercept_enabled"
        const val KEY_RUNNING_PREFIX = "running_"
    }
}
