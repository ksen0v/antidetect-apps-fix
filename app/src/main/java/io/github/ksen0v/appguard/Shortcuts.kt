package io.github.ksen0v.appguard

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap

object Shortcuts {

    private const val ICON_SIZE_PX = 192

    /** Просит лаунчер закрепить ярлык, который запускает [pkg] через AppGuard. */
    fun requestPin(context: Context, pkg: String) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, LaunchActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(LaunchActivity.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val label = AppInfo.label(context, pkg)
        val builder = ShortcutInfoCompat.Builder(context, "guard_$pkg")
            .setShortLabel(context.getString(R.string.shortcut_label, label))
            .setLongLabel(context.getString(R.string.shortcut_label, label))
            .setIntent(intent)
        AppInfo.icon(context, pkg)?.let {
            builder.setIcon(IconCompat.createWithBitmap(it.toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)))
        }
        ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
    }
}
