package dev.pampa.pampai.core.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

/** Una notifica attiva, gia' ridotta a parole. */
data class ActiveNotification(
  val key: String,
  val packageName: String,
  val appLabel: String,
  val title: String?,
  val text: String?,
  val whenMillis: Long,
  val ongoing: Boolean,
  val category: String?,
  val isGroupSummary: Boolean,
)

/** La musica di qualsiasi app, letta dalla sua sessione multimediale. */
data class SystemMedia(val packageName: String, val appLabel: String, val title: String?, val artist: String?, val album: String?, val playing: Boolean, val positionMillis: Long, val durationMillis: Long)

/**
 * L'accesso alle notifiche: il sistema lo collega solo se l'utente lo attiva nelle impostazioni;
 * da quel momento le notifiche attive si leggono da qui e si possono chiudere. Con lo stesso
 * accesso si vedono le sessioni multimediali di tutte le app (la musica di Spotify, YouTube…).
 */
class PampaiNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    instance = WeakReference(this)
  }

  override fun onListenerDisconnected() {
    instance = null
  }

  private fun active(): List<ActiveNotification> {
    val notifications = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
    return notifications.mapNotNull { it.toModel(this) }.sortedByDescending { it.whenMillis }
  }

  private fun StatusBarNotification.toModel(context: Context): ActiveNotification? {
    val extras = notification.extras ?: return null
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
    if (title.isNullOrBlank() && text.isNullOrBlank()) return null
    val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
    return ActiveNotification(
      key = key,
      packageName = packageName,
      appLabel = label,
      title = title,
      text = text,
      whenMillis = notification.`when`.takeIf { it > 0 } ?: postTime,
      ongoing = isOngoing,
      category = notification.category,
      isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
    )
  }

  companion object {
    @Volatile private var instance: WeakReference<PampaiNotificationListener>? = null

    val connected: Boolean get() = instance?.get() != null

    fun component(context: Context) = ComponentName(context, PampaiNotificationListener::class.java)

    fun activeNotifications(): List<ActiveNotification>? = instance?.get()?.active()

    fun dismiss(key: String): Boolean {
      val listener = instance?.get() ?: return false
      return runCatching { listener.cancelNotification(key); true }.getOrDefault(false)
    }

    /** Le sessioni multimediali attive: chiede lo stesso accesso alle notifiche. */
    fun media(context: Context): List<SystemMedia>? {
      val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
      val controllers = runCatching { manager.getActiveSessions(component(context)) }.getOrNull() ?: return null
      return controllers.mapNotNull { it.toMedia(context) }
    }

    fun controller(context: Context, packageName: String?): MediaController? {
      val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
      val controllers = runCatching { manager.getActiveSessions(component(context)) }.getOrNull() ?: return null
      return if (packageName == null) controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
      else controllers.firstOrNull { it.packageName == packageName }
    }

    private fun MediaController.toMedia(context: Context): SystemMedia? {
      val metadata = metadata
      val state = playbackState
      val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
      return SystemMedia(
        packageName = packageName,
        appLabel = label,
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        playing = state?.state == PlaybackState.STATE_PLAYING,
        positionMillis = state?.position ?: 0L,
        durationMillis = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
      )
    }
  }
}
