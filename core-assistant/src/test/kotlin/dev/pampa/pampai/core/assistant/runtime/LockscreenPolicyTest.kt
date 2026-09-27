package dev.pampa.pampai.core.assistant.runtime

import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LockscreenPolicyTest {

  private val locked = LockscreenPolicy.restrict(RegistryHolder().catalog.value.registry)

  @Test
  fun `dal telefono bloccato restano sveglie, torcia, calcoli e web`() {
    listOf("timer_crea", "sveglia_crea", "torcia", "volume", "batteria", "calcola", "cerca_web").forEach { assertNotNull(it, locked.find(it)) }
  }

  @Test
  fun `e sparisce tutto cio' che e' personale`() {
    listOf("notifiche_recenti", "eventi_calendario", "ricorda", "promemoria_elenco", "posizione", "apri_app", "schermo_leggi").forEach { assertNull(it, locked.find(it)) }
  }
}
