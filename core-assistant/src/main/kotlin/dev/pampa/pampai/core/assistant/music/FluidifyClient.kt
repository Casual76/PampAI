package dev.pampa.pampai.core.assistant.music

import dev.pampa.pampai.core.assistant.tools.music.readLibraryPages
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Un brano o un nodo dell'albero di Fluidify, in parole. */
data class MusicItem(val mediaId: String, val title: String, val artist: String?, val album: String?, val browsable: Boolean, val durationMillis: Long?)

/** Cosa sta suonando adesso. */
data class NowPlaying(val item: MusicItem?, val playing: Boolean, val positionMillis: Long, val durationMillis: Long, val shuffle: Boolean, val repeat: Int, val queueSize: Int, val queueIndex: Int)

/**
 * Il controllo di Fluidify via `MediaBrowser` (media3): il suo `PlaybackService` e' una
 * `MediaLibraryService` esportata, con un albero (`sq/playlists`, `sq/recent`, `sq/downloads`),
 * la ricerca, e tre comandi custom (shuffle, ripeti, radio). Tutto passa dal thread principale,
 * come vuole media3; la connessione e' pigra e scade in cinque secondi.
 */
@Singleton
class FluidifyClient @Inject constructor(@ApplicationContext private val context: Context) {

  private var browser: MediaBrowser? = null

  val installed: Boolean get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

  private suspend fun connect(): MediaBrowser? = withContext(Dispatchers.Main) {
    browser?.takeIf { it.isConnected }?.let { return@withContext it }
    browser?.release()
    browser = null
    if (!installed) return@withContext null
    val token = SessionToken(context, ComponentName(PACKAGE, SERVICE))
    val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
      runCatching { MediaBrowser.Builder(context, token).buildAsync().await() }.getOrNull()
    }
    browser = connected
    connected
  }

  /** Esegue [block] sul browser connesso, sul thread principale; null se Fluidify non risponde. */
  private suspend fun <T> withBrowser(block: suspend (MediaBrowser) -> T): T? {
    val b = connect() ?: return null
    return withContext(Dispatchers.Main) { try { block(b) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null } }
  }

  suspend fun nowPlaying(): NowPlaying? = withBrowser { b ->
    NowPlaying(
      item = b.currentMediaItem?.toMusicItem(),
      playing = b.isPlaying,
      positionMillis = b.currentPosition,
      durationMillis = b.duration.takeIf { it > 0 } ?: 0L,
      shuffle = b.shuffleModeEnabled,
      repeat = b.repeatMode,
      queueSize = b.mediaItemCount,
      queueIndex = b.currentMediaItemIndex,
    )
  }

  suspend fun play(): Boolean = withBrowser { it.play(); true } ?: false
  suspend fun pause(): Boolean = withBrowser { it.pause(); true } ?: false
  suspend fun next(): Boolean = withBrowser { it.seekToNext(); true } ?: false
  suspend fun previous(): Boolean = withBrowser { it.seekToPrevious(); true } ?: false
  suspend fun seekTo(positionMillis: Long): Boolean = withBrowser { it.seekTo(positionMillis.coerceAtLeast(0L)); true } ?: false
  suspend fun stop(): Boolean = withBrowser { it.pause(); it.seekTo(0L); true } ?: false

  suspend fun setShuffle(enabled: Boolean): Boolean = withBrowser { it.shuffleModeEnabled = enabled; true } ?: false

  suspend fun setRepeat(mode: Int): Boolean = withBrowser { it.repeatMode = mode; true } ?: false

  suspend fun queue(limit: Int = 30): List<MusicItem>? = withBrowser { b ->
    val from = b.currentMediaItemIndex.coerceAtLeast(0)
    (from until minOf(b.mediaItemCount, from + limit)).map { b.getMediaItemAt(it).toMusicItem() }
  }

  /** Zero-based pages. A failed later page invalidates the read, never masquerades as empty. */
  suspend fun children(parentId: String, limit: Int = 50): List<MusicItem>? = withBrowser { b ->
    readLibraryPages(limit) { page, size ->
      val result = b.getChildren(parentId, page, size, null).await()
      if (result.resultCode != androidx.media3.session.LibraryResult.RESULT_SUCCESS) null
      else result.value?.map { it.toMusicItem() }
    }
  }

  suspend fun item(id: String): MusicItem? = withBrowser { b ->
    val result = b.getItem(id).await()
    check(result.resultCode == androidx.media3.session.LibraryResult.RESULT_SUCCESS)
    checkNotNull(result.value).toMusicItem()
  }

  suspend fun search(query: String, limit: Int = 30): List<MusicItem>? = withBrowser { b ->
    check(b.search(query, null).await().resultCode == androidx.media3.session.LibraryResult.RESULT_SUCCESS)
    val result = b.getSearchResult(query, 0, limit, null).await()
    check(result.resultCode == androidx.media3.session.LibraryResult.RESULT_SUCCESS)
    checkNotNull(result.value).map { it.toMusicItem() }
  }

  /** Riproduce un elemento dell'albero: Fluidify espande da solo playlist e nodi in una coda. */
  suspend fun playItem(mediaId: String): Boolean = withBrowser { b ->
    b.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
    b.prepare()
    b.play()
    true
  } ?: false

  /** Il ripiego: l'intent di riproduzione da ricerca, che Fluidify gestisce anche da chiusa. */
  fun playFromSearch(query: String): Boolean {
    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
      .setPackage(PACKAGE)
      .putExtra(android.app.SearchManager.QUERY, query)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
  }

  /** Un comando custom di Fluidify; false se non lo accetta. */
  suspend fun custom(command: String, args: Bundle = Bundle.EMPTY): Boolean = withBrowser { b ->
    val result = b.sendCustomCommand(SessionCommand(command, Bundle.EMPTY), args).await()
    result.resultCode == SessionResult.RESULT_SUCCESS
  } ?: false

  fun open(): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
  }

  fun release() {
    browser?.release()
    browser = null
  }

  @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
  private fun MediaItem.toMusicItem(): MusicItem = MusicItem(
    mediaId = mediaId,
    title = mediaMetadata.title?.toString() ?: mediaId,
    artist = mediaMetadata.artist?.toString(),
    album = mediaMetadata.albumTitle?.toString(),
    browsable = mediaMetadata.isBrowsable == true,
    durationMillis = mediaMetadata.durationMs,
  )

  companion object {
    const val PACKAGE = "dev.pampa.fluidify"
    const val SERVICE = "dev.lelonio.square.playback.PlaybackService"
    const val CMD_SHUFFLE = "dev.lelonio.square.SHUFFLE"
    const val CMD_REPEAT = "dev.lelonio.square.REPEAT"
    const val CMD_RADIO = "dev.lelonio.square.RADIO"
    const val CMD_LIKE = "dev.lelonio.square.LIKE"
    const val CMD_IS_LIKED = "dev.lelonio.square.IS_LIKED"
    const val ID_PLAYLISTS = "sq/playlists"
    const val ID_RECENT = "sq/recent"
    const val ID_DOWNLOADS = "sq/downloads"
    const val CONNECT_TIMEOUT_MILLIS = 5_000L
    val REPEAT_LABELS = mapOf(Player.REPEAT_MODE_OFF to "spento", Player.REPEAT_MODE_ONE to "un brano", Player.REPEAT_MODE_ALL to "tutto")
  }
}
