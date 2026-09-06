package dev.pampa.pampai.core.assistant.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampai.core.assistant.R
import dev.pampa.pampai.core.assistant.db.ReminderDao
import dev.pampa.pampai.core.assistant.db.ReminderEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Come si ripete un promemoria. */
enum class ReminderRepeat(val label: String) {
  NONE("una volta"), DAILY("ogni giorno"), WEEKDAYS("dal lunedi' al venerdi'"), WEEKLY("ogni settimana");

  companion object {
    fun parse(raw: String?): ReminderRepeat = when (raw?.lowercase()?.trim()) {
      null, "", "no", "none", "mai", "una volta" -> NONE
      "daily", "giornaliero", "ogni giorno", "tutti i giorni" -> DAILY
      "weekdays", "feriali", "lun-ven", "giorni feriali" -> WEEKDAYS
      "weekly", "settimanale", "ogni settimana" -> WEEKLY
      else -> NONE
    }

    /** La prossima occorrenza dopo [from] per una ripetizione, alla stessa ora. */
    fun next(repeat: ReminderRepeat, atMillis: Long, from: Long, zone: ZoneId): Long? {
      if (repeat == NONE) return null
      var candidate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
      val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
      repeat(400) {
        candidate = when (repeat) {
          DAILY, WEEKDAYS -> candidate.plusDays(1)
          WEEKLY -> candidate.plusWeeks(1)
          NONE -> return null
        }
        val weekday = candidate.dayOfWeek != DayOfWeek.SATURDAY && candidate.dayOfWeek != DayOfWeek.SUNDAY
        if (candidate.isAfter(now) && (repeat != WEEKDAYS || weekday)) return candidate.toInstant().toEpochMilli()
      }
      return null
    }
  }
}

/**
 * I promemoria di PampAI: una riga in Room e una sveglia esatta di `AlarmManager` che, all'ora,
 * manda una notifica con "Fatto" e "Fra 10 min". Quelli ricorrenti si ripianificano da soli;
 * al riavvio del telefono si rimettono tutti in coda.
 */
@Singleton
class ReminderRepository @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dao: ReminderDao,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val scheduler = ReminderScheduler(context)

  fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()

  suspend fun list(): List<ReminderEntity> = dao.listEnabled()

  suspend fun get(id: Long): ReminderEntity? = dao.get(id)

  suspend fun add(text: String, atMillis: Long, repeat: ReminderRepeat): ReminderEntity {
    val id = dao.insert(ReminderEntity(text = text, atMillis = atMillis, repeat = repeat.name))
    val entity = dao.get(id)!!
    scheduler.schedule(entity)
    return entity
  }

  suspend fun remove(id: Long): Boolean {
    val entity = dao.get(id) ?: return false
    scheduler.cancel(entity.id)
    dao.delete(id)
    return true
  }

  suspend fun snooze(id: Long, minutes: Int) {
    val entity = dao.get(id) ?: return
    val updated = entity.copy(atMillis = System.currentTimeMillis() + minutes * 60_000L, enabled = true)
    dao.update(updated)
    scheduler.schedule(updated)
  }

  /** E' suonato: quelli ricorrenti passano alla prossima volta, gli altri si spengono. */
  suspend fun fired(id: Long) {
    val entity = dao.get(id) ?: return
    val next = ReminderRepeat.next(ReminderRepeat.valueOf(entity.repeat), entity.atMillis, System.currentTimeMillis(), ZoneId.systemDefault())
    val updated = if (next != null) entity.copy(atMillis = next, lastFiredAtMillis = System.currentTimeMillis()) else entity.copy(enabled = false, lastFiredAtMillis = System.currentTimeMillis())
    dao.update(updated)
    if (next != null) scheduler.schedule(updated)
  }

  suspend fun rescheduleAll() {
    val now = System.currentTimeMillis()
    dao.listEnabled().forEach { entity ->
      if (entity.atMillis > now) {
        scheduler.schedule(entity)
      } else {
        // Scaduto mentre il telefono era spento: suona adesso, poi la logica delle ripetizioni fa il resto.
        scheduler.schedule(entity.copy(atMillis = now + 5_000L))
      }
    }
  }

  fun rescheduleAllAsync() {
    scope.launch { runCatching { rescheduleAll() } }
  }

  val exactAllowed: Boolean get() = scheduler.exactAllowed
}

/** La sveglia di sistema per un promemoria: esatta se il telefono lo consente, altrimenti "quasi". */
class ReminderScheduler(private val context: Context) {

  private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

  val exactAllowed: Boolean get() = Build.VERSION.SDK_INT < 31 || alarms?.canScheduleExactAlarms() == true

  fun schedule(entity: ReminderEntity) {
    val manager = alarms ?: return
    val pending = pendingIntent(entity.id)
    runCatching {
      if (exactAllowed) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entity.atMillis, pending)
      else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entity.atMillis, pending)
    }
  }

  fun cancel(id: Long) {
    alarms?.cancel(pendingIntent(id))
  }

  private fun pendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
    context,
    id.toInt(),
    Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

/** Suona il promemoria (notifica con Fatto / Fra 10 min) e gestisce i due tasti. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

  @Inject lateinit var reminders: ReminderRepository

  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getLongExtra(EXTRA_ID, -1L)
    if (id < 0) return
    val result = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        when (intent.action) {
          ACTION_FIRE -> {
            val entity = reminders.get(id) ?: return@launch
            notify(context, entity)
            reminders.fired(id)
          }
          ACTION_DONE -> context.getSystemService(NotificationManager::class.java)?.cancel(TAG, id.toInt())
          ACTION_SNOOZE -> {
            context.getSystemService(NotificationManager::class.java)?.cancel(TAG, id.toInt())
            reminders.snooze(id, 10)
          }
        }
      } finally {
        result.finish()
      }
    }
  }

  private fun notify(context: Context, entity: ReminderEntity) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Promemoria", NotificationManager.IMPORTANCE_HIGH).apply { description = "I promemoria chiesti ad Aria" })
    fun action(action: String, request: Int) = PendingIntent.getBroadcast(
      context,
      (entity.id * 10 + request).toInt(),
      Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, entity.id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
      PendingIntent.getActivity(context, entity.id.toInt(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val notification = Notification.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_aria)
      .setContentTitle("Promemoria")
      .setContentText(entity.text)
      .setStyle(Notification.BigTextStyle().bigText(entity.text))
      .setCategory(Notification.CATEGORY_REMINDER)
      .setAutoCancel(true)
      .apply { launch?.let { setContentIntent(it) } }
      .addAction(Notification.Action.Builder(null, "Fatto", action(ACTION_DONE, 1)).build())
      .addAction(Notification.Action.Builder(null, "Fra 10 min", action(ACTION_SNOOZE, 2)).build())
      .build()
    runCatching { manager.notify(TAG, entity.id.toInt(), notification) }
  }

  companion object {
    const val ACTION_FIRE = "dev.pampa.pampai.reminder.FIRE"
    const val ACTION_DONE = "dev.pampa.pampai.reminder.DONE"
    const val ACTION_SNOOZE = "dev.pampa.pampai.reminder.SNOOZE"
    const val EXTRA_ID = "dev.pampa.pampai.reminder.ID"
    const val CHANNEL = "promemoria"
    const val TAG = "promemoria"
  }
}

/** Al riavvio (e dopo un aggiornamento dell'app) le sveglie di sistema spariscono: si rimettono. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

  @Inject lateinit var reminders: ReminderRepository

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
      val result = goAsync()
      CoroutineScope(Dispatchers.IO).launch {
        try {
          runCatching { reminders.rescheduleAll() }
        } finally {
          result.finish()
        }
      }
    }
  }
}

/** Per i test JVM: quale sarebbe la prossima occorrenza, senza Android. */
internal fun nextOccurrenceForTest(repeat: ReminderRepeat, atMillis: Long, from: Long, zone: ZoneId): Long? = ReminderRepeat.next(repeat, atMillis, from, zone)

@Suppress("unused")
private fun keepRunBlockingImport() = runBlocking { }
