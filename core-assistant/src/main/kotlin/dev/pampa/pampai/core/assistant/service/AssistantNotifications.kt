package dev.pampa.pampai.core.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.pampa.pampai.core.assistant.R
import dev.pampa.pampai.core.assistant.runtime.ExecutionResult
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le due notifiche di Aria: quella di avanzamento, che tiene vivo il service e dice cosa sta
 * facendo, e quella della risposta, che compare solo se l'app non e' davanti e riapre la
 * conversazione. Stesso canale, importanza bassa: e' lavoro chiesto dall'utente, non un allarme.
 */
@Singleton
class AssistantNotifications @Inject constructor(@ApplicationContext private val context: Context) {

  private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

  fun ensureChannel() {
    val manager = manager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Aria", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Avanzamento e risposte di Aria."
        },
      )
    }
  }

  fun progress(question: String, status: String): Notification {
    ensureChannel()
    val stop = PendingIntent.getService(
      context, 1,
      Intent(context, AssistantForegroundService::class.java).setAction(AssistantForegroundService.ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_aria)
      .setContentTitle(question.take(60))
      .setContentText(status)
      .setStyle(NotificationCompat.BigTextStyle().bigText(status))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setProgress(0, 0, true)
      .addAction(0, "Ferma", stop)
      .setContentIntent(openConversation(null))
      .build()
  }

  private fun canPost(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
  }

  /** L'unico punto che chiama `notify`: il controllo del permesso sta qui, e lint lo vede qui. */
  @SuppressLint("MissingPermission")
  private fun post(id: Int, notification: Notification) {
    if (!canPost()) return
    runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
  }

  fun updateProgress(question: String, status: String) = post(PROGRESS_ID, progress(question, status))

  fun showResult(result: ExecutionResult) {
    if (result.cancelled) return
    ensureChannel()
    val text = result.answer?.let { firstLines(it) } ?: failureText(result.failure ?: FailureKind.UNKNOWN)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_aria)
      .setContentTitle(if (result.answer != null) "Aria ha risposto" else "Aria si e' fermata")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text).setSummaryText(result.question.take(80)))
      .setAutoCancel(true)
      .setContentIntent(openConversation(result.conversationId))
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .build()
    post(RESULT_ID, notification)
  }

  fun cancelProgress() {
    runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_ID) }
  }

  /** La riga di stato dal `statusKey` dello stato: la stessa lingua della card, senza risorse. */
  fun statusLine(state: AssistantState): String = when (state) {
    is AssistantState.Classifying -> "Capisco cosa serve…"
    is AssistantState.Working -> statusFor(state.statusKey, state.tier)
    is AssistantState.WaitingRateLimit -> "${state.provider.label} e' al limite: riprovo fra ${state.secondsLeft} s"
    is AssistantState.SwitchingProvider -> "Passo a ${state.to.label}…"
    is AssistantState.Answering -> "Rispondo…"
    is AssistantState.AwaitingConfirmation -> "Aspetto la tua conferma nell'app"
    is AssistantState.Done -> "Fatto"
    is AssistantState.Failed -> failureText(state.kind)
    is AssistantState.Cancelled -> "Fermata"
    else -> "Un momento…"
  }

  fun failureText(kind: FailureKind): String = when (kind) {
    FailureKind.NO_KEYS -> "Nessuna chiave verificata: apri le impostazioni."
    FailureKind.UNAUTHORIZED -> "La chiave non e' piu' valida: controllala nelle impostazioni."
    FailureKind.RATE_LIMITED -> "Il servizio e' al limite di richieste: riprova fra poco (o accendi la riserva automatica nelle impostazioni)."
    FailureKind.NETWORK -> "Niente rete."
    FailureKind.TIMEOUT -> "Ci ha messo troppo: riprova con una domanda piu' semplice."
    FailureKind.BLOCKED -> "Il servizio ha rifiutato la richiesta."
    FailureKind.PROVIDER -> "Il servizio ha risposto con un errore."
    FailureKind.MICROPHONE -> "Il microfono non e' disponibile."
    FailureKind.TRANSCRIPTION -> "Non sono riuscita a trascrivere."
    FailureKind.UNKNOWN -> "Qualcosa e' andato storto."
  }

  private fun firstLines(answer: String): String = answer.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n").take(400)

  private fun openConversation(conversationId: Long?): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (conversationId != null) launch.putExtra(EXTRA_CONVERSATION, conversationId)
    return PendingIntent.getActivity(context, (conversationId ?: 0L).toInt() + 100, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  companion object {
    const val CHANNEL_ID = "aria"
    const val PROGRESS_ID = 4101
    const val RESULT_ID = 4102

    /** L'extra con cui una notifica (o una scorciatoia) chiede di aprire una conversazione. */
    const val EXTRA_CONVERSATION = "dev.pampa.pampai.extra.CONVERSATION"

    /** Le frasi di stato per i gruppi locali; le app collegate portano le loro `statusKey`, che qui hanno una frase generica. */
    fun statusFor(key: String, tier: ModelTier): String {
      val base = when (key) {
        "thinking" -> if (tier == ModelTier.DEEP) "Analizzo con calma…" else "Penso…"
        "more_tools" -> "Mi serve dell'altro…"
        "deep_model" -> "Passo al modello piu' capace…"
        PampaiGroup.ARIA.statusKey -> "Guardo fra le mie cose…"
        PampaiGroup.OROLOGIO.statusKey -> "Sistemo l'orologio…"
        PampaiGroup.PROMEMORIA.statusKey -> "Segno il promemoria…"
        PampaiGroup.CALENDARIO.statusKey -> "Guardo il calendario…"
        PampaiGroup.CONTATTI.statusKey -> "Cerco fra i contatti…"
        PampaiGroup.NOTIFICHE.statusKey -> "Leggo le notifiche…"
        PampaiGroup.SISTEMA.statusKey -> "Tocco il telefono…"
        PampaiGroup.APRI.statusKey -> "Apro…"
        PampaiGroup.INFO.statusKey -> "Controllo il telefono…"
        PampaiGroup.WEB.statusKey -> "Cerco sul web…"
        PampaiGroup.CALCOLO.statusKey -> "Faccio i conti…"
        PampaiGroup.SCHERMO.statusKey -> "Guardo lo schermo…"
        PampaiGroup.RIPRODUZIONE.statusKey, PampaiGroup.LIBRERIA.statusKey -> "Parlo con Fluidify…"
        "grades" -> "Guardo i voti…"
        "agenda", "day" -> "Guardo l'agenda…"
        "lessons" -> "Guardo l'orario…"
        "board" -> "Leggo la bacheca…"
        "absences" -> "Controllo le assenze…"
        "stats" -> "Faccio i conti…"
        "materials" -> "Cerco fra i materiali…"
        "hourly", "daily", "nowcast", "precip", "air", "sky", "providers", "alerts" -> "Guardo il meteo…"
        "schedule", "live", "journey", "places", "routines" -> "Guardo i bus…"
        "store" -> "Guardo lo store…"
        "convert" -> "Converto…"
        else -> if (key == "app") "Agisco nell'app…" else "Uso gli strumenti…"
      }
      return if (tier == ModelTier.DEEP && key != "thinking") "$base (modello profondo)" else base
    }
  }
}
