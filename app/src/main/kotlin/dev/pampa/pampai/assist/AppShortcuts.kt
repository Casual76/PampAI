package dev.pampa.pampai.assist

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.pampa.pampai.MainActivity
import dev.pampa.pampai.R

/**
 * Le scorciatoie dell'icona (pressione lunga): nuova chat, parla, ultima conversazione. Dinamiche e
 * non statiche perche' le statiche vogliono il package nell'XML, e la build di debug ne ha un altro.
 */
object AppShortcuts {
  fun publish(context: Context) {
    fun intent(vararg extras: Pair<String, Boolean>) = Intent(context, MainActivity::class.java)
      .setAction(Intent.ACTION_VIEW)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .apply { extras.forEach { (key, value) -> putExtra(key, value) } }

    val shortcuts = listOf(
      ShortcutInfoCompat.Builder(context, "parla")
        .setShortLabel("Parla con Aria")
        .setLongLabel("Parla con Aria")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_mic))
        .setIntent(intent(MainActivity.EXTRA_VOICE to true, MainActivity.EXTRA_NEW to true))
        .build(),
      ShortcutInfoCompat.Builder(context, "nuova")
        .setShortLabel("Nuova chat")
        .setLongLabel("Nuova conversazione")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
        .setIntent(intent(MainActivity.EXTRA_NEW to true))
        .build(),
      ShortcutInfoCompat.Builder(context, "ultima")
        .setShortLabel("Ultima")
        .setLongLabel("Ultima conversazione")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_history))
        .setIntent(intent(MainActivity.EXTRA_LAST to true))
        .build(),
    )
    runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
  }
}
