package io.github.ksen0v.appguard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch

/** Невидимая точка входа для ярлыков: закрывает второе приложение пары и открывает выбранное. */
class LaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_PACKAGE)
        if (target == null || savedInstanceState != null) {
            finish()
            return
        }
        Guard.scope.launch {
            val result = Guard.launchExclusive(this@LaunchActivity, target)
            if (result is Guard.Result.Failed) {
                Toast.makeText(applicationContext, result.message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "io.github.ksen0v.appguard.extra.PACKAGE"
    }
}
