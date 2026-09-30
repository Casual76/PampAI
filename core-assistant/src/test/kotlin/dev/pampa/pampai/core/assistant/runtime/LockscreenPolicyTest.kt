package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.bridge.RemoteCategory
import dev.antigravity.fluidengine.ai.bridge.RemoteGroup
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.pampai.core.assistant.tools.PampaiCategory
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockscreenPolicyTest {

  private val full = RegistryHolder().catalog.value.registry
  private val locked = LockscreenPolicy.restrict(full)

  private class Fake(override val name: String, override val group: AiToolGroup) : AiTool<PampaiToolContext> {
    override val description = "prova"
    override val parameters = Schema.obj(emptyMap())
    override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = ToolOutput("fatto: niente")
  }

  @Test
  fun `dal telefono bloccato restano creare sveglie e timer, torcia, volume, calcoli, web e musica`() {
    listOf(
      "timer_crea", "sveglia_crea", "sveglia_posticipa", "torcia", "volume", "batteria",
      "calcola", "converti_unita", "valuta", "fuso_orario", "data_calcola",
      "cerca_web", "leggi_pagina", "wikipedia", "definizione",
      "musica_riproduci", "musica_controllo", "musica_adesso", "musica_volume",
    ).forEach { assertNotNull(it, locked.find(it)) }
  }

  @Test
  fun `e sparisce tutto cio' che e' personale o cambia come il telefono avvisa`() {
    listOf(
      // Le decisioni del proprietario: suoneria, non disturbare, togliere sveglie e timer, schermo.
      "non_disturbare", "modalita_suoneria", "sveglia_disattiva", "timer_elimina", "luminosita", "rotazione",
      "musica_salva", "sveglia_prossima", "musica_coda",
      // Il personale.
      "notifiche_recenti", "notifica_chiudi", "media_sistema", "contatto_cerca", "chiama", "messaggio_prepara",
      "eventi_calendario", "evento_crea", "promemoria_elenco", "promemoria_crea", "ricorda", "dimentica", "memoria_elenco",
      "conversazioni_cerca", "posizione", "app_collegate", "aiuto", "impostazioni_pampai", "consumo",
      "apri_app", "apri_url", "app_installate", "schermo_leggi", "schermo_guarda", "dispositivo_info",
      "musica_cerca", "musica_playlist", "musica_recenti",
    ).forEach { assertNull(it, locked.find(it)) }
  }

  @Test
  fun `e' un elenco di nomi ammessi, e ognuno esiste davvero`() {
    // Nessuno strumento passa per il gruppo: solo per nome.
    locked.tools.forEach { assertTrue(it.name, it.name in LockscreenPolicy.allowedTools) }
    // Un nome rinominato nel catalogo non deve sparire in silenzio dall'elenco.
    LockscreenPolicy.allowedTools.forEach { assertNotNull(it, full.find(it)) }
    assertEquals(LockscreenPolicy.allowedTools.size, locked.tools.size)
  }

  @Test
  fun `uno strumento nuovo resta fuori finche' nessuno lo ammette`() {
    val registry = ToolRegistry<PampaiToolContext>(
      tools = listOf(Fake("torcia", PampaiGroup.SISTEMA), Fake("sirena", PampaiGroup.SISTEMA), Fake("orario_negozi", PampaiGroup.WEB)),
      groups = listOf(PampaiGroup.SISTEMA, PampaiGroup.WEB),
    )
    val restricted = LockscreenPolicy.restrict(registry)
    assertNotNull(restricted.find("torcia"))
    assertNull(restricted.find("sirena"))
    assertNull(restricted.find("orario_negozi"))
    // E il gruppo rimasto senza strumenti sparisce anche dal menu'.
    assertEquals(listOf(PampaiGroup.SISTEMA), restricted.groups)
  }

  @Test
  fun `un'app collegata non passa nemmeno con un nome ammesso`() {
    val category = RemoteCategory("conti", "Conti", "i conti")
    val group = RemoteGroup("conti_base", "calc", "i conti", category, loadsWithCategory = true, remoteId = "base")
    val registry = ToolRegistry<PampaiToolContext>(tools = listOf(Fake("calcola", group)), groups = listOf(group))
    assertNull(LockscreenPolicy.restrict(registry).find("calcola"))
  }

  @Test
  fun `nel menu' da bloccato non restano gruppi vuoti`() {
    locked.groups.forEach { assertTrue(it.id, locked.toolCount(setOf(it)) > 0) }
    val categories = locked.categories.map { it.id }.toSet()
    assertFalse(PampaiCategory.ARIA.id in categories)
    assertFalse(PampaiCategory.SCHERMO.id in categories)
    assertFalse(locked.groups.any { it == PampaiGroup.NOTIFICHE || it == PampaiGroup.CONTATTI || it == PampaiGroup.APRI })
  }

  @Test
  fun `da bloccato il volume e' solo quello dei media`() {
    val volume = locked.find("volume")!!
    assertFalse(volume.spec.parameters.toString().contains("flusso"))
    assertNotNull(LockscreenPolicy.volumeRefusal(buildJsonObject { put("livello", "0"); put("flusso", "suoneria") }))
    assertNotNull(LockscreenPolicy.volumeRefusal(buildJsonObject { put("flusso", "sveglia") }))
    assertNull(LockscreenPolicy.volumeRefusal(buildJsonObject { put("livello", "su"); put("flusso", "media") }))
    assertNull(LockscreenPolicy.volumeRefusal(buildJsonObject { put("livello", "su") }))
  }
}
