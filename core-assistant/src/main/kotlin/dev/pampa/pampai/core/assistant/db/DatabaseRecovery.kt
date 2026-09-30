package dev.pampa.pampai.core.assistant.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il database non si e' aperto (una migrazione fallita, un file rovinato) ed e' stato messo da
 * parte: [movedTo] e' il nome con cui i file vecchi restano nella cartella dei database, [reason]
 * l'errore in breve. La UI lo mostra come avviso: le conversazioni non sono sparite, sono li'.
 */
data class RecoveryNotice(val atMillis: Long, val movedTo: String, val reason: String)

/**
 * L'avviso di un database ripartito da zero. Vive in un file accanto al database e non solo in
 * memoria: se il processo muore prima che la UI lo mostri (e al primo avvio dopo un aggiornamento
 * succede spesso), all'avvio dopo e' ancora li'. Sparisce solo con [dismiss].
 */
@Singleton
class DatabaseRecovery @Inject constructor(@ApplicationContext private val context: Context) {

  private val marker: File get() = File(context.getDatabasePath(PAMPAI_DB_NAME).parentFile, "$PAMPAI_DB_NAME.recovered")

  private val flow = MutableStateFlow(read())

  /** L'avviso da mostrare, o null se il database si e' aperto come doveva. */
  val notice: StateFlow<RecoveryNotice?> = flow

  /** L'utente l'ha visto: non si mostra piu'. I file messi da parte restano dove sono. */
  fun dismiss() {
    runCatching { marker.delete() }
    flow.value = null
  }

  internal fun report(notice: RecoveryNotice) {
    runCatching { marker.writeText("${notice.atMillis}\n${notice.movedTo}\n${notice.reason.replace('\n', ' ').take(300)}") }
    flow.value = notice
  }

  private fun read(): RecoveryNotice? = runCatching {
    if (!marker.exists()) return@runCatching null
    val lines = marker.readLines()
    RecoveryNotice(lines.getOrNull(0)?.toLongOrNull() ?: 0L, lines.getOrNull(1).orEmpty(), lines.getOrNull(2).orEmpty())
  }.getOrNull()
}

/** Il nome del file del database, in un posto solo: lo usano l'apertura, la copia di sicurezza e l'avviso. */
const val PAMPAI_DB_NAME = "pampai.db"
