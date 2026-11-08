package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampai.core.assistant.durable.DurableFoundation
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private val Context.researchConnections by preferencesDataStore("aria_research_connections")
/** Explicit interactive connection scopes survive restart independently of autonomous setup. */
@Singleton class ResearchConnections @Inject constructor(@ApplicationContext context: Context, private val foundation: DurableFoundation) {
  private val store = context.researchConnections
  internal suspend fun save(read: ResearchRead) {
    val key = stringPreferencesKey(read.grantId)
    val encoded = researchJson.encodeToString(ResearchRead.serializer(), read)
    if (store.data.first()[key] == encoded) return
    foundation.changePolicy { store.edit { it[key] = encoded } }
  }
  val reads: Flow<List<ResearchRead>> = combine(store.data, foundation.grantsFlow) { saved, grants ->
    val enabled = grants.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
    saved.asMap().values.filterIsInstance<String>()
      .map { researchJson.decodeFromString<ResearchRead>(it) }.filter { it.grantId in enabled }
  }.distinctUntilChanged()
  suspend fun authorizedReads(): List<ResearchRead> = reads.first()
}
