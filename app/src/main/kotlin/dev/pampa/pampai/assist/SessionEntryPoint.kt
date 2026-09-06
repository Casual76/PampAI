package dev.pampa.pampai.assist

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore

/** Cio' che la sessione di sistema prende dal grafo di Hilt: non e' un'Activity, quindi se lo cerca da sola. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEntryPoint {
  fun runtime(): AssistantRuntime
  fun conversations(): ConversationsRepository
  fun screen(): ScreenContextStore
  fun pampaiSettings(): PampaiSettingsStore
  fun engineSettings(): EngineSettingsStore
}
