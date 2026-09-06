package dev.pampa.pampai.core.assistant.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un allegato che l'utente ha messo nella domanda, prima di essere salvato: gia' ridotto a una misura che viaggia. */
class PendingAttachment(val kind: AttachmentKind, val mime: String, val name: String, val bytes: ByteArray) {
  fun toPart(): ContentPart = when (kind) {
    AttachmentKind.IMAGE -> ContentPart.Image(bytes, mime)
    AttachmentKind.DOCUMENT -> ContentPart.Document(bytes, mime, name)
  }
}

/**
 * Prepara gli allegati per il modello: le immagini si rimpiccioliscono (i provider vogliono base64
 * piccoli: Groq sotto i 4 MB), i PDF si estraggono in testo per i modelli che non li leggono.
 */
@Singleton
class AttachmentReader @Inject constructor(@ApplicationContext private val context: Context) {

  private val pdfReady: Boolean by lazy { runCatching { PDFBoxResourceLoader.init(context); true }.getOrDefault(false) }

  /** Un'immagine ridotta a JPEG con il lato lungo entro [maxSide]: quello che si manda a un modello che vede. */
  suspend fun shrinkImage(bytes: ByteArray, maxSide: Int = 1_600, quality: Int = 82): ByteArray = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (largest / sample > maxSide * 2) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext bytes
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()
    out.toByteArray()
  }

  /** Il testo di un PDF, per il modello che non legge documenti; null se non si estrae niente. */
  suspend fun pdfText(bytes: ByteArray, maxPages: Int = 40): String? = withContext(Dispatchers.IO) {
    if (!pdfReady) return@withContext null
    runCatching {
      PDDocument.load(bytes).use { document ->
        val stripper = PDFTextStripper().apply { startPage = 1; endPage = minOf(document.numberOfPages, maxPages) }
        stripper.getText(document).trim().takeIf { it.isNotBlank() }
      }
    }.getOrNull()
  }

  /** La traduzione in testo di una parte che il modello non regge: i PDF si', le immagini no. */
  suspend fun fallbackText(part: ContentPart): String? = when (part) {
    is ContentPart.Document -> if (part.mime.contains("pdf")) pdfText(part.bytes) else runCatching { String(part.bytes, Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    is ContentPart.Text -> part.text
    is ContentPart.Image -> null
  }
}
