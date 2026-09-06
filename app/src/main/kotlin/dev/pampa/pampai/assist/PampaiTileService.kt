package dev.pampa.pampai.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.pampa.pampai.MainActivity

/** Il tile nelle Impostazioni rapide: apre la sessione sopra quello che c'e', o la chat dell'app. */
class PampaiTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    qsTile?.apply {
      state = Tile.STATE_ACTIVE
      label = "Aria"
      if (Build.VERSION.SDK_INT >= 29) subtitle = if (PampaiInteractionService.isActive(this@PampaiTileService)) "Assistente" else "PampAI"
      updateTile()
    }
  }

  override fun onClick() {
    super.onClick()
    // Il pannello delle impostazioni rapide sta sopra tutto: la sessione si apre solo dopo che si e' chiuso.
    if (PampaiInteractionService.isActive(this)) {
      unlockAndRun { PampaiInteractionService.show(this, PampaiInteractionService.SOURCE_TILE) }
      return
    }
    val intent = Intent(this, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(MainActivity.EXTRA_VOICE, true)
      .putExtra(MainActivity.EXTRA_NEW, true)
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}
