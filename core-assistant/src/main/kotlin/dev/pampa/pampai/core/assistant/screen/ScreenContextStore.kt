package dev.pampa.pampai.core.assistant.screen

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Una finestra letta dallo schermo: il pacchetto che la disegna e le sue righe di testo. */
data class AssistWindow(val packageName: String?, val title: String?, val lines: List<String>)

/**
 * Cosa il sistema ha consegnato alla sessione: lo screenshot (se "Usa screenshot" e' acceso e la
 * finestra non e' protetta), la struttura delle finestre (il testo che c'e' scritto), il pacchetto
 * in primo piano. Vive quanto la sessione e' mostrata: si azzera quando si nasconde, perche' lo
 * schermo di dieci minuti fa non e' "lo schermo".
 */
@Singleton
class ScreenContextStore @Inject constructor() {

  data class Snapshot(
    val shownAtMillis: Long = 0L,
    val screenshot: Bitmap? = null,
    val screenshotExpected: Boolean = false,
    val assistExpected: Boolean = false,
    val windows: List<AssistWindow> = emptyList(),
    val assistArrived: Boolean = false,
    val lockscreen: Boolean = false,
    val source: String = "assist",
  ) {
    val foregroundPackage: String? get() = windows.firstOrNull { it.packageName != null }?.packageName
    val available: Boolean get() = shownAtMillis > 0L
  }

  private val stateFlow = MutableStateFlow(Snapshot())
  val state: StateFlow<Snapshot> = stateFlow
  val current: Snapshot get() = stateFlow.value

  /** La sessione si sta mostrando: cosa il sistema ha promesso di mandare. */
  fun beginShow(hasAssist: Boolean, hasScreenshot: Boolean, source: String, lockscreen: Boolean) {
    stateFlow.value = Snapshot(shownAtMillis = System.currentTimeMillis(), screenshotExpected = hasScreenshot, assistExpected = hasAssist, source = source, lockscreen = lockscreen)
  }

  fun setScreenshot(bitmap: Bitmap?) {
    stateFlow.value = stateFlow.value.copy(screenshot = bitmap)
  }

  /** Una finestra in piu': il sistema le manda una alla volta (`onHandleAssist` per ogni finestra). */
  fun addWindow(structure: AssistStructure?, packageName: String?) {
    val window = AssistStructureReader.read(structure, packageName)
    val current = stateFlow.value
    stateFlow.value = current.copy(windows = current.windows + window, assistArrived = true)
  }

  fun markAssistDone() {
    stateFlow.value = stateFlow.value.copy(assistArrived = true)
  }

  fun setLockscreen(shown: Boolean) {
    stateFlow.value = stateFlow.value.copy(lockscreen = shown)
  }

  fun clear() {
    stateFlow.value.screenshot?.let { runCatching { it.recycle() } }
    stateFlow.value = Snapshot()
  }

  /** La struttura puo' arrivare dopo `onShow`: chi la vuole aspetta al massimo [timeoutMillis]. */
  suspend fun awaitAssist(timeoutMillis: Long = 1_500L): Snapshot {
    val now = stateFlow.value
    if (!now.assistExpected || now.assistArrived) return now
    return withTimeoutOrNull(timeoutMillis) { stateFlow.first { it.assistArrived || !it.assistExpected } } ?: stateFlow.value
  }

  /** Lo screenshot intero come JPEG ridotto, per un allegato. */
  fun screenshotJpeg(maxSide: Int = 1_280, quality: Int = 80): ByteArray? = current.screenshot?.let { ScreenCropper.encode(it, null, maxSide, quality) }

  /** Una porzione dello screenshot, ritagliata col dito, come JPEG. */
  fun cropJpeg(rect: Rect, maxSide: Int = 1_280, quality: Int = 80): ByteArray? = current.screenshot?.let { ScreenCropper.encode(it, rect, maxSide, quality) }
}

/** Ritaglia e riduce uno screenshot: JPEG con il lato lungo entro il massimo, per viaggiare leggero. */
object ScreenCropper {
  fun encode(bitmap: Bitmap, rect: Rect?, maxSide: Int, quality: Int): ByteArray? {
    val bounded = rect?.let {
      Rect(it.left.coerceIn(0, bitmap.width - 1), it.top.coerceIn(0, bitmap.height - 1), it.right.coerceIn(1, bitmap.width), it.bottom.coerceIn(1, bitmap.height))
    }
    if (bounded != null && (bounded.width() < 8 || bounded.height() < 8)) return null
    val source = if (bounded == null) bitmap else Bitmap.createBitmap(bitmap, bounded.left, bounded.top, bounded.width(), bounded.height())
    val largest = maxOf(source.width, source.height)
    val scaled = if (largest > maxSide) {
      val scale = maxSide.toFloat() / largest
      Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else {
      source
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== source) scaled.recycle()
    if (source !== bitmap) source.recycle()
    return out.toByteArray()
  }
}

/**
 * Cammina la struttura di una finestra e ne tira fuori le righe leggibili: testo, descrizioni,
 * suggerimenti e valori dei campi, dall'alto in basso, senza doppioni. I nodi che l'app ha
 * marcato `isAssistBlocked` restano fuori (e' la sua scelta, non la nostra).
 */
object AssistStructureReader {
  fun read(structure: AssistStructure?, packageName: String?): AssistWindow {
    if (structure == null) return AssistWindow(packageName, null, emptyList())
    val lines = LinkedHashSet<String>()
    var title: String? = null
    val pkg = packageName ?: structure.activityComponent?.packageName
    for (i in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(i)
      if (title == null) title = window.title?.toString()?.takeIf { it.isNotBlank() }
      walk(window.rootViewNode, lines, 0)
    }
    return AssistWindow(pkg, title, lines.toList())
  }

  private fun walk(node: AssistStructure.ViewNode?, out: MutableSet<String>, depth: Int) {
    node ?: return
    if (depth > 60 || node.isAssistBlocked || node.visibility != android.view.View.VISIBLE) return
    val texts = listOfNotNull(
      node.text?.toString(),
      node.contentDescription?.toString(),
      node.hint?.takeIf { node.text.isNullOrBlank() },
    )
    texts.forEach { raw ->
      val text = raw.replace(Regex("\\s+"), " ").trim()
      if (text.length in 1..400) out += text
    }
    for (i in 0 until node.childCount) walk(node.getChildAt(i), out, depth + 1)
  }
}
