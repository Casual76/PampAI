package dev.pampa.pampai.core.assistant.tools.device

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.StatFs
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

private fun Long.gb(): String = String.format(Locale.ITALIAN, "%.1f GB", this / 1_073_741_824.0)

class BatteriaTool : AiTool<PampaiToolContext> {
  override val name = "batteria"
  override val group: AiToolGroup = PampaiGroup.INFO
  override val description = "Il livello della batteria, se e' in carica, la temperatura e lo stato. Per \"quanta batteria ho?\", \"si sta caricando?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val manager = ctx.app.getSystemService(BatteryManager::class.java)
    val sticky = ctx.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: sticky?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
    val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }
    val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
    val saver = ctx.app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
    return ToolText.output {
      line("batteria", if (level >= 0) "$level%" else "sconosciuta")
      line("stato", when (status) { BatteryManager.BATTERY_STATUS_CHARGING -> "in carica"; BatteryManager.BATTERY_STATUS_FULL -> "carica completa"; BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "non in carica"; else -> "sconosciuto" })
      if (plugged != 0) line("collegata a", when (plugged) { BatteryManager.BATTERY_PLUGGED_AC -> "presa"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "ricarica senza fili"; else -> "alimentazione" })
      temperature?.let { line("temperatura", String.format(Locale.ITALIAN, "%.1f °C", it)) }
      if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) line("nota", "surriscaldata")
      line("risparmio energetico", if (saver) "attivo" else "spento")
    }
  }
}

class DispositivoInfoTool : AiTool<PampaiToolContext> {
  override val name = "dispositivo_info"
  override val group: AiToolGroup = PampaiGroup.INFO
  override val description = "Modello del telefono, marca, versione di Android, memoria RAM. Per \"che telefono ho?\", \"che versione di Android?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val memory = ActivityManager.MemoryInfo().also { ctx.app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
    return ToolText.output {
      line("telefono", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
      line("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      if (Build.VERSION.SECURITY_PATCH.isNotBlank()) line("patch di sicurezza", Build.VERSION.SECURITY_PATCH)
      line("ram", "${memory.availMem.gb()} liberi di ${memory.totalMem.gb()}")
      line("pampai", runCatching { ctx.app.packageManager.getPackageInfo(ctx.app.packageName, 0).versionName }.getOrNull() ?: "—")
    }
  }
}

class MemoriaDispositivoTool : AiTool<PampaiToolContext> {
  override val name = "memoria_dispositivo"
  override val group: AiToolGroup = PampaiGroup.INFO
  override val description = "Lo spazio di archiviazione: usato e libero. Per \"quanto spazio ho?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = withContext(Dispatchers.IO) {
    val data = StatFs(Environment.getDataDirectory().path)
    val total = data.totalBytes
    val free = data.availableBytes
    ToolText.output {
      line("spazio totale", total.gb())
      line("libero", free.gb())
      line("usato", "${(total - free).gb()} (${((total - free) * 100 / total.coerceAtLeast(1))}%)")
      if (free < 2_000_000_000L) line("nota", "meno di 2 GB liberi: il telefono potrebbe rallentare")
    }
  }
}

class PosizioneTool : AiTool<PampaiToolContext> {
  override val name = "posizione"
  override val group: AiToolGroup = PampaiGroup.INFO
  override val description = "Dove si trova il telefono adesso: coordinate e, se possibile, indirizzo o citta'. Per \"dove sono?\", \"in che via sono?\". Le app meteo e bus hanno gia' la posizione: usa questo solo per la domanda diretta."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "leggere la posizione", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)?.let { return it }
    val manager = ctx.app.getSystemService(LocationManager::class.java) ?: return ToolOutput.error("posizione non disponibile")
    if (!manager.isLocationEnabled) return ToolOutput.error("la posizione e' spenta nelle impostazioni del telefono: l'utente puo' accenderla (apri_impostazioni posizione)")
    val location = current(manager) ?: return ToolOutput.error("nessuna posizione recente: prova fra qualche secondo, o all'aperto")
    val address = withContext(Dispatchers.IO) {
      runCatching {
        @Suppress("DEPRECATION")
        Geocoder(ctx.app, ctx.locale).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
      }.getOrNull()
    }
    val ageMinutes = (System.currentTimeMillis() - location.time) / 60_000
    return ToolText.output {
      line("coordinate", String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude))
      address?.let { a ->
        line("indirizzo", listOfNotNull(a.thoroughfare?.let { t -> t + (a.subThoroughfare?.let { " $it" } ?: "") }, a.locality, a.postalCode, a.countryName).joinToString(", "))
      }
      line("precisione", "${location.accuracy.toInt()} m" + if (ageMinutes > 2) " (di $ageMinutes minuti fa)" else "")
      line("mappa", "https://maps.google.com/?q=${location.latitude},${location.longitude}")
    }
  }

  private suspend fun current(manager: LocationManager): Location? {
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { manager.isProviderEnabled(it) }
    if (Build.VERSION.SDK_INT >= 30) {
      for (provider in providers.take(2)) {
        val fresh = withTimeoutOrNull(8_000L) {
          suspendCancellableCoroutine<Location?> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching { manager.getCurrentLocation(provider, signal, Executors.newSingleThreadExecutor()) { location -> if (continuation.isActive) continuation.resume(location) } }
              .onFailure { if (continuation.isActive) continuation.resume(null) }
          }
        }
        if (fresh != null) return fresh
      }
    }
    return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
  }
}

fun infoTools(): List<AiTool<PampaiToolContext>> = listOf(BatteriaTool(), DispositivoInfoTool(), MemoriaDispositivoTool(), PosizioneTool())
