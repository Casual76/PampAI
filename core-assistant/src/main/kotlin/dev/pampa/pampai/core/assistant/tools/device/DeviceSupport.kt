package dev.pampa.pampai.core.assistant.tools.device

import android.content.Intent
import android.content.pm.PackageManager
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text

/** Le piccole cose che tutti i tool del telefono fanno: aprire un intent, chiedere un permesso, i nomi delle app. */
internal object Device {

  /** Fa partire un intent da fuori un'Activity; false se nessuna app lo gestisce. */
  fun launch(ctx: PampaiToolContext, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(ctx.app.packageManager) == null) return false
    return runCatching { ctx.app.startActivity(intent); true }.getOrDefault(false)
  }

  /** Un permesso a runtime: vero se c'e' o arriva; altrimenti il testo per il modello. */
  suspend fun permission(ctx: PampaiToolContext, what: String, vararg permissions: String): ToolOutput? =
    if (ctx.permissions.ensure(what, *permissions)) null else ToolOutput.error(ctx.permissions.missingText(what))

  fun appLabel(ctx: PampaiToolContext, packageName: String): String =
    runCatching { ctx.app.packageManager.getApplicationLabel(ctx.app.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

  /** Le app con un'icona nel launcher: etichetta → pacchetto, in ordine di etichetta. */
  fun launchableApps(pm: PackageManager): List<Pair<String, String>> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
      .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
      .distinctBy { it.second }
      .sortedBy { Text.normalize(it.first) }
  }

  /** L'app che somiglia di piu' a un nome: prima i nomi Pampa, poi l'etichetta esatta, poi il prefisso, poi il punteggio. */
  fun findApp(pm: PackageManager, name: String): Pair<String, String>? {
    val wanted = Text.normalize(name)
    KNOWN[wanted]?.let { pkg -> if (installed(pm, pkg)) return (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) to pkg }
    val apps = launchableApps(pm)
    apps.firstOrNull { Text.normalize(it.first) == wanted }?.let { return it }
    apps.firstOrNull { Text.normalize(it.first).startsWith(wanted) }?.let { return it }
    return apps.filter { Text.matches(wanted, it.first) }.maxByOrNull { Text.score(wanted, it.first) }
  }

  fun installed(pm: PackageManager, packageName: String): Boolean = runCatching { pm.getPackageInfo(packageName, 0); true }.getOrDefault(false)

  val KNOWN: Map<String, String> = mapOf(
    "classeviva" to "dev.antigravity.classevivaexpressive", "cv" to "dev.antigravity.classevivaexpressive", "registro" to "dev.antigravity.classevivaexpressive",
    "meteo" to "dev.pampa.fluidweather", "fluid weather" to "dev.pampa.fluidweather", "fluidweather" to "dev.pampa.fluidweather",
    "bus" to "dev.antigravity.fluidtransit", "transit" to "dev.antigravity.fluidtransit", "fluid transit" to "dev.antigravity.fluidtransit", "autobus" to "dev.antigravity.fluidtransit",
    "convert" to "com.p2r3.convert", "convert to it" to "com.p2r3.convert", "convertitore" to "com.p2r3.convert",
    "store" to "com.pampa.store", "pampa store" to "com.pampa.store",
    "musica" to "dev.pampa.fluidify", "fluidify" to "dev.pampa.fluidify", "spotify" to "com.spotify.music",
    "pampai" to "dev.pampa.pampai", "aria" to "dev.pampa.pampai",
    "whatsapp" to "com.whatsapp", "telegram" to "org.telegram.messenger", "youtube" to "com.google.android.youtube",
    "maps" to "com.google.android.apps.maps", "mappe" to "com.google.android.apps.maps", "chrome" to "com.android.chrome",
    "fotocamera" to "com.android.camera", "orologio" to "com.google.android.deskclock", "calendario" to "com.google.android.calendar",
    "gmail" to "com.google.android.gm", "instagram" to "com.instagram.android", "impostazioni" to "com.android.settings",
  )
}
