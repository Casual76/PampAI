package dev.pampa.pampai.core.assistant.tools.screen

import android.content.pm.PackageManager
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Surface
import kotlinx.serialization.json.JsonObject

private const val NO_SESSION = "nessuno schermo da leggere: Aria vede lo schermo solo quando e' richiamata dal tasto di accensione sopra un'altra app (o se l'utente allega lo schermo dalla barra)"
private const val NO_ASSIST = "il sistema non ha passato il testo dello schermo: l'utente deve attivare \"Usa testo dallo schermo\" nelle impostazioni dell'assistente digitale [[impostazioni:assistente]]"
private const val NO_SCREENSHOT = "nessuno screenshot: o \"Usa screenshot\" e' spento nelle impostazioni dell'assistente [[impostazioni:assistente]], o la schermata e' protetta (banca, password) o e' il blocco schermo"

private fun PampaiToolContext.screenOrNull(): ScreenContextStore? = screen?.takeIf { surface == Surface.SESSION && it.current.available }

/** Il nome leggibile di un pacchetto, per dire "sei su Fluidify" invece di "dev.pampa.fluidify". */
internal fun PampaiToolContext.appLabel(packageName: String?): String? = packageName?.let { pkg ->
  runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
}

/** Il testo che c'e' scritto sullo schermo sotto la sessione, a pagine. */
class SchermoLeggiTool : AiTool<PampaiToolContext> {
  override val name = "schermo_leggi"
  override val group: AiToolGroup = PampaiGroup.SCHERMO
  override val description = "Legge il testo visibile sullo schermo sotto Aria (l'app che l'utente stava usando): titoli, righe, campi. A pagine, se e' lungo. Per \"cosa c'e' scritto qui\", \"riassumi questa pagina\", \"di cosa parla\"."
  override val parameters = Schema.obj(mapOf("pagina" to Schema.int("la pagina, da 1 (default 1)")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val screen = ctx.screenOrNull() ?: return ToolOutput.error(NO_SESSION)
    val snapshot = screen.awaitAssist()
    if (snapshot.lockscreen) return ToolOutput.error("il telefono e' bloccato: lo schermo non si legge dal blocco")
    if (!snapshot.assistExpected) return ToolOutput.error(NO_ASSIST)
    val lines = snapshot.windows.flatMap { it.lines }
    if (lines.isEmpty()) return ToolOutput.error("lo schermo non ha testo leggibile (e' un'immagine, un video o una pagina che non lo espone): prova schermo_guarda")
    val pages = paginate(lines, ToolText.MAX_CHARS - 200)
    val page = (args.int("pagina") ?: 1).coerceIn(1, pages.size)
    val appName = ctx.appLabel(snapshot.foregroundPackage) ?: snapshot.foregroundPackage ?: "app sconosciuta"
    val title = snapshot.windows.firstOrNull { it.title != null }?.title
    return ToolText.output(ToolText.MAX_CHARS) {
      line("app", appName + (title?.let { " · $it" } ?: ""))
      line("pagina", "$page/${pages.size}")
      pages[page - 1].forEach { line(it) }
    }
  }

  private fun paginate(lines: List<String>, maxChars: Int): List<List<String>> {
    val pages = mutableListOf<MutableList<String>>(mutableListOf())
    var count = 0
    lines.forEach { line ->
      if (count + line.length + 1 > maxChars && pages.last().isNotEmpty()) {
        pages += mutableListOf<String>()
        count = 0
      }
      pages.last() += line
      count += line.length + 1
    }
    return pages
  }
}

/** Lo screenshot al modello che vede: quando il testo non basta (grafici, foto, layout). */
class SchermoGuardaTool : AiTool<PampaiToolContext> {
  override val name = "schermo_guarda"
  override val group: AiToolGroup = PampaiGroup.SCHERMO
  override val description = "Passa lo screenshot dello schermo sotto Aria a un modello che vede le immagini: per grafici, foto, layout, o quando schermo_leggi non basta. Costa di piu': prima prova schermo_leggi."
  override val parameters = Schema.obj(mapOf("cosa" to Schema.str("cosa guardare in particolare (facoltativo)")))
  override val longRunning = false

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val screen = ctx.screenOrNull() ?: return ToolOutput.error(NO_SESSION)
    val snapshot = screen.current
    if (snapshot.lockscreen) return ToolOutput.error("il telefono e' bloccato: niente screenshot dal blocco")
    val jpeg = screen.screenshotJpeg() ?: return ToolOutput.error(NO_SCREENSHOT)
    val appName = ctx.appLabel(snapshot.foregroundPackage) ?: snapshot.foregroundPackage ?: "app sconosciuta"
    val focus = args.str("cosa")?.let { " Guarda in particolare: $it." } ?: ""
    return ToolOutput(
      text = "ecco lo screenshot dello schermo (app: $appName).$focus Descrivi o rispondi in base a cio' che vedi.",
      parts = listOf(ContentPart.Image(jpeg, "image/jpeg")),
      escalate = true,
    )
  }
}

/** Quale app c'e' sotto: per "dove sono", o per decidere quale categoria aprire. */
class AppInPrimoPianoTool : AiTool<PampaiToolContext> {
  override val name = "app_in_primo_piano"
  override val group: AiToolGroup = PampaiGroup.SCHERMO
  override val description = "Dice quale app l'utente stava usando quando ha richiamato Aria (nome e pacchetto), e se lo schermo era leggibile."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val screen = ctx.screenOrNull() ?: return ToolOutput.error(NO_SESSION)
    val snapshot = screen.awaitAssist(800)
    val pkg = snapshot.foregroundPackage
    return ToolText.output {
      line("app", ctx.appLabel(pkg) ?: pkg ?: "sconosciuta")
      pkg?.let { line("pacchetto", it) }
      line("testo leggibile", if (snapshot.windows.any { it.lines.isNotEmpty() }) "si'" else "no")
      line("screenshot", if (snapshot.screenshot != null) "si'" else "no")
      if (snapshot.lockscreen) line("nota", "schermo bloccato")
    }
  }
}

/** Il testo dello schermo, da tradurre: e' `schermo_leggi` con l'istruzione gia' dentro. */
class TraduciSchermoTool : AiTool<PampaiToolContext> {
  override val name = "traduci_schermo"
  override val group: AiToolGroup = PampaiGroup.SCHERMO
  override val description = "Prende il testo dello schermo sotto Aria per tradurlo nella lingua chiesta (default italiano). Per \"traduci questa pagina\", \"cosa dice in italiano\"."
  override val parameters = Schema.obj(mapOf("lingua" to Schema.str("la lingua di arrivo (default: italiano)"), "pagina" to Schema.int("la pagina del testo, da 1")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val inner = SchermoLeggiTool().run(args, ctx)
    if (inner.text.startsWith("errore")) return inner
    val language = args.str("lingua") ?: "italiano"
    return ToolOutput("traduci in $language il testo che segue, mantenendo l'ordine e i titoli; se e' gia' in $language dillo.\n" + inner.text)
  }
}

fun screenTools(): List<AiTool<PampaiToolContext>> = listOf(SchermoLeggiTool(), SchermoGuardaTool(), AppInPrimoPianoTool(), TraduciSchermoTool())

/** Vero se un pacchetto e' installato: per dire "sei su X" solo se X esiste. */
internal fun PackageManager.isInstalled(packageName: String): Boolean = runCatching { getPackageInfo(packageName, 0); true }.getOrDefault(false)
