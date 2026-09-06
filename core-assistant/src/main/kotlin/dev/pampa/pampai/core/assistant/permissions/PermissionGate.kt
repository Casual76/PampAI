package dev.pampa.pampai.core.assistant.permissions

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * I permessi Android chiesti da un tool mentre gira: il tool chiama [ensure], e se il permesso
 * manca compare una richiesta che `MainActivity` (quando e' davanti) traduce nel dialogo di sistema;
 * il tool aspetta al massimo 45 secondi e poi risponde "permesso mancante", mai un'eccezione.
 * Nella sessione di sistema non si puo' chiedere: il tool lo dice, e l'utente lo concede dall'app.
 */
@Singleton
class PermissionGate @Inject constructor(@ApplicationContext private val context: Context) {

  /** Una richiesta in attesa: i permessi e il motivo, in parole, per il testo sopra il dialogo. */
  data class Request(val id: Long, val permissions: List<String>, val reason: String)

  private val ids = AtomicLong(0)
  private val pendingFlow = MutableStateFlow<Request?>(null)
  val pending: StateFlow<Request?> = pendingFlow
  private val results = MutableStateFlow<Pair<Long, Boolean>?>(null)

  /** Vero mentre un'Activity dell'app puo' mostrare il dialogo di sistema. */
  @Volatile var handlerAttached: Boolean = false

  fun has(vararg permissions: String): Boolean = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

  /**
   * Vero se i permessi ci sono o l'utente li ha appena concessi; falso se li ha negati, se non c'e'
   * nessuno che possa chiederli (la sessione sopra un'altra app), o se non ha risposto in tempo.
   */
  suspend fun ensure(reason: String, vararg permissions: String, timeoutMillis: Long = 45_000L): Boolean {
    if (has(*permissions)) return true
    if (!handlerAttached) return false
    val request = Request(ids.incrementAndGet(), permissions.toList(), reason)
    pendingFlow.value = request
    val granted = withTimeoutOrNull(timeoutMillis) { results.first { it?.first == request.id }?.second } ?: false
    if (pendingFlow.value?.id == request.id) pendingFlow.value = null
    return granted || has(*permissions)
  }

  /** L'Activity ha ricevuto l'esito del dialogo. */
  fun resolve(id: Long, granted: Boolean) {
    results.value = id to granted
    if (pendingFlow.value?.id == id) pendingFlow.value = null
  }

  /** Il testo per il modello quando un permesso manca: cosa manca e dove si concede. */
  fun missingText(what: String): String =
    if (handlerAttached) "permesso mancante: $what (l'utente non l'ha concesso)" else "permesso mancante: $what. Si concede aprendo PampAI: Impostazioni > Permessi [[impostazioni:permessi]]"
}

/** Gli accessi speciali che non passano dal dialogo dei permessi: solo una pagina delle impostazioni. */
enum class SpecialAccess(val label: String) {
  NOTIFICATIONS("accesso alle notifiche"),
  WRITE_SETTINGS("modifica delle impostazioni di sistema"),
  DND("accesso alla modalita' Non disturbare"),
  EXACT_ALARM("sveglie e promemoria precisi");

  fun granted(context: Context): Boolean = when (this) {
    NOTIFICATIONS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    WRITE_SETTINGS -> Settings.System.canWrite(context)
    DND -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    EXACT_ALARM -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
  }

  fun intent(context: Context): Intent = when (this) {
    NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
  }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Il testo per il modello quando manca. */
  fun missingText(): String = "serve l'$label: l'utente lo attiva da PampAI, Impostazioni > Permessi [[impostazioni:permessi]]"
}
