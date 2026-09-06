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
}
