package dev.pampa.pampai.feature.assistant.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.pampa.pampai.core.assistant.db.Attachment
import java.io.File

/** I file degli allegati visti da fuori: la foto che la fotocamera deve riempire, il documento da aprire. */
internal object AttachmentFiles {

  private fun authority(context: Context) = "${context.packageName}.files"

  /** Un file vuoto per la fotocamera, in cache: l'app che scatta ci scrive la foto intera. */
  fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    // Le foto di prima non servono piu': sono gia' state copiate negli allegati o scartate.
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
    val file = File(dir, "foto-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, authority(context), file)
  }

  /** Apre un allegato salvato con l'app che lo sa mostrare (un PDF, un testo). */
  fun open(context: Context, attachment: Attachment) {
    val file = File(attachment.path)
    if (!file.exists()) {
      Toast.makeText(context, "Il file non c'e' piu'", Toast.LENGTH_SHORT).show()
      return
    }
    val uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, attachment.mime)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(intent, attachment.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      .onFailure { Toast.makeText(context, "Nessuna app sa aprire questo file", Toast.LENGTH_SHORT).show() }
  }
}
