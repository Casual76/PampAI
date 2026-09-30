package dev.pampa.pampai.core.assistant.tools

import dev.antigravity.fluidengine.ai.bridge.BridgeCalls
import dev.antigravity.fluidengine.ai.bridge.RemoteCatalog
import dev.antigravity.fluidengine.ai.bridge.RemoteCategoryInfo
import dev.antigravity.fluidengine.ai.bridge.RemoteGroupInfo
import dev.antigravity.fluidengine.ai.bridge.RemotePart
import dev.antigravity.fluidengine.ai.bridge.RemoteResult
import dev.antigravity.fluidengine.ai.bridge.RemoteToolHost
import dev.antigravity.fluidengine.ai.bridge.RemoteToolInfo
import dev.antigravity.fluidengine.ai.bridge.RemoteToolSet
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.pampa.pampai.core.assistant.prompt.PreRouter
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryHolderTest {

  private object NoCalls : BridgeCalls {
    override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?>? = null
    override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult = RemoteResult.Done("eco", false, emptyList())
    override suspend fun status(authority: String, jobId: String): RemoteResult = RemoteResult.Error("no")
    override suspend fun cancel(authority: String, jobId: String) = Unit
    override fun readPart(part: RemotePart): ByteArray? = null
  }

  private val host = object : RemoteToolHost<PampaiToolContext> {
    override suspend fun confirm(ctx: PampaiToolContext, toolName: String, text: ConfirmationText): ToolOutput? = null
  }

  private val catalog = RemoteCatalog(
    protocol = 1,
    authority = "dev.antigravity.classevivaexpressive.ai.tools",
    packageName = "dev.antigravity.classevivaexpressive",
    appId = "dev.antigravity.classevivaexpressive",
    domain = "cv",
    appLabel = "ClasseViva Expressive",
    appVersion = "7.4.0",
    hint = "il registro",
    vocabulary = listOf("Matematica", "Storia dell'arte"),
    categories = listOf(RemoteCategoryInfo("classeviva", "ClasseViva", "il registro elettronico")),
    groups = listOf(RemoteGroupInfo("voti", "grades", "i voti", "classeviva", null, loadsWithCategory = true, action = false)),
    tools = listOf(RemoteToolInfo("voti_media", "voti", "la media", Schema.obj(emptyMap()), needsConfirmation = false, longRunning = false, action = false)),
  )

  @Test
  fun localCatalogHasAllCategoriesAndNoRemoteRules() {
    val holder = RegistryHolder()
    val mounted = holder.catalog.value
    // 72 tool locali: aria 8, calcolo 4, web 5, schermo 4, dispositivo 36, musica 15.
    assertEquals(72, mounted.registry.tools.size)
    assertEquals(PampaiCategory.entries.map { it.id }.toSet(), mounted.categories.map { it.id }.toSet())
    assertTrue(mounted.preRules.isEmpty())
    assertTrue(mounted.connectedPackages.isEmpty())
    assertNotNull(mounted.registry.find("schermo_leggi"))
    assertNotNull(mounted.registry.find("musica_riproduci"))
  }

  @Test
  fun remoteCatalogMountsPrefixedGroupsAndVocabularyRules() {
    val holder = RegistryHolder()
    holder.setRemote(listOf(RemoteToolSet.of(NoCalls, catalog, host)))
    val mounted = holder.catalog.value
    assertNotNull(mounted.registry.find("cv_voti_media"))
    assertTrue(mounted.categories.any { it.id == "classeviva" })
    assertEquals(setOf("dev.antigravity.classevivaexpressive"), mounted.connectedPackages)
    // Due regole per app: le parole del mestiere e il vocabolario che manda l'app.
    assertEquals(2, mounted.preRules.size)
    // La regola del vocabolario decide il gruppo remoto senza il router: "la media di matematica".
    val verdict = PreRouter(mounted.preRules).decide("che media ho in matematica?", actionsEnabled = true)
    assertTrue(verdict.groups.any { it.id == "cv_voti" })
    // E la chat normale non si fa distrarre.
    assertFalse(PreRouter(mounted.preRules).decide("ciao come va", actionsEnabled = true).groups.any { it.id == "cv_voti" })
    // Le parole del mestiere bastano da sole: "che voti ho preso" non nomina nessuna materia.
    assertTrue(PreRouter(mounted.preRules).decide("che voti ho preso?", actionsEnabled = true).groups.any { it.id == "cv_voti" })
    // Smontare riporta al catalogo locale.
    holder.setRemote(emptyList())
    assertTrue(holder.catalog.value.registry.find("cv_voti_media") == null)
  }

  private val weather = catalog.copy(
    authority = "dev.pampa.fluidweather.ai.tools",
    packageName = "dev.pampa.fluidweather",
    appId = "dev.pampa.fluidweather",
    domain = "meteo",
    appLabel = "Fluid Weather",
    vocabulary = emptyList(),
    categories = listOf(RemoteCategoryInfo("meteo", "Meteo", "il meteo")),
    groups = listOf(RemoteGroupInfo("oggi", "weather", "il meteo di oggi", "meteo", null, loadsWithCategory = true, action = false)),
    tools = listOf(RemoteToolInfo("adesso", "oggi", "il meteo adesso", Schema.obj(emptyMap()), needsConfirmation = false, longRunning = false, action = false)),
  )

  private fun debugOf(release: RemoteCatalog): RemoteCatalog =
    release.copy(packageName = "${release.packageName}.debug", authority = "${release.packageName}.debug.ai.tools", appLabel = "${release.appLabel} (debug)")

  @Test
  fun debugAndReleaseOfTheSameAppMountOnceAndTheOthersStay() {
    val holder = RegistryHolder()
    // La debug arriva per prima: vince lo stesso la release.
    holder.setRemote(listOf(RemoteToolSet.of(NoCalls, debugOf(catalog), host), RemoteToolSet.of(NoCalls, catalog, host), RemoteToolSet.of(NoCalls, weather, host)))
    val mounted = holder.catalog.value
    assertEquals(1, mounted.registry.tools.count { it.name == "cv_voti_media" })
    assertNotNull(mounted.registry.find("meteo_adesso"))
    assertEquals(setOf("dev.antigravity.classevivaexpressive", "dev.pampa.fluidweather"), mounted.connectedPackages)
    // Con la sola debug installata, la debug si monta.
    holder.setRemote(listOf(RemoteToolSet.of(NoCalls, debugOf(catalog), host)))
    assertEquals(setOf("dev.antigravity.classevivaexpressive.debug"), holder.catalog.value.connectedPackages)
  }

  @Test
  fun aClashingAppStaysOutAloneAndSaysWhy() {
    // "cerca" + "web" fa "cerca_web", che e' gia' uno strumento di Aria.
    val clashing = weather.copy(
      packageName = "dev.x.cerca",
      domain = "cerca",
      categories = listOf(RemoteCategoryInfo("cerca", "Cerca", "cerca")),
      groups = listOf(RemoteGroupInfo("base", "web", "cerca", "cerca", null, loadsWithCategory = true, action = false)),
      tools = listOf(RemoteToolInfo("web", "base", "cerca", Schema.obj(emptyMap()), needsConfirmation = false, longRunning = false, action = false)),
    )
    val warnings = mutableListOf<String>()
    val sets = listOf(RemoteToolSet.of(NoCalls, clashing, host), RemoteToolSet.of(NoCalls, catalog, host), RemoteToolSet.of(NoCalls, debugOf(catalog), host))
    val kept = mountable(RegistryHolder().catalog.value.registry.tools, sets) { warnings += it }
    assertEquals(listOf("dev.antigravity.classevivaexpressive"), kept.map { it.catalog.packageName })
    assertEquals(2, warnings.size)
    assertTrue(warnings.any { it.startsWith("dev.x.cerca") && it.contains("cerca_web") })
    assertTrue(warnings.any { it.startsWith("dev.antigravity.classevivaexpressive.debug") })
    // Dal registry: niente eccezioni, e le app buone ci sono.
    val holder = RegistryHolder()
    holder.setRemote(sets)
    assertNotNull(holder.catalog.value.registry.find("cv_voti_media"))
    assertEquals(1, holder.catalog.value.registry.tools.count { it.name == "cerca_web" })
  }

  @Test
  fun aParentGroupWithoutToolsOfItsOwnStaysDeclared() {
    val nested = catalog.copy(
      groups = listOf(
        RemoteGroupInfo("scuola", "school", "la scuola", "classeviva", null, loadsWithCategory = true, action = false),
        RemoteGroupInfo("voti", "grades", "i voti", "classeviva", "scuola", loadsWithCategory = false, action = false),
      ),
    )
    val holder = RegistryHolder()
    holder.setRemote(listOf(RemoteToolSet.of(NoCalls, nested, host)))
    val mounted = holder.catalog.value
    assertNotNull(mounted.registry.find("cv_voti_media"))
    assertNotNull(mounted.registry.group("cv_scuola"))
  }

  @Test
  fun theLockscreenCatalogHasNoConnectedApps() {
    val holder = RegistryHolder()
    holder.setRemote(listOf(RemoteToolSet.of(NoCalls, catalog, host), RemoteToolSet.of(NoCalls, weather, host)))
    val locked = holder.catalog.value.lockscreen
    assertNull(locked.registry.find("cv_voti_media"))
    assertNull(locked.registry.find("meteo_adesso"))
    assertNotNull(locked.registry.find("timer_crea"))
    assertTrue(locked.connectedPackages.isEmpty())
    assertTrue(locked.preRules.isEmpty())
    assertFalse(locked.summary.contains("classeviva"))
    assertFalse(locked.summary.contains("notifiche"))
    // Il router da bloccato sceglie solo fra i gruppi rimasti.
    val schema = locked.router.schema.toString()
    assertTrue(schema.contains("\"orologio\""))
    assertFalse(schema.contains("\"notifiche\""))
    assertFalse(schema.contains("\"cv_voti\""))
    assertFalse(schema.contains("\"aria\""))
    // Ed e' lo stesso oggetto a ogni domanda, finche' il catalogo non cambia.
    assertTrue(holder.catalog.value.lockscreen === locked)
  }
}
