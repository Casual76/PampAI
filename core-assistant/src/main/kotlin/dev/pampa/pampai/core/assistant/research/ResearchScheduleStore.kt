package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private val Context.researchSchedule by preferencesDataStore("aria_research_schedule")
@Serializable enum class ScheduleState { OFF, SUSPENDED, WAITING, ENQUEUED, RUNNING, COMPLETED }
@Serializable data class AreaScheduleStatus(
  val area: String, val state: ScheduleState = ScheduleState.OFF, val reason: String? = null,
  val lastAttemptAt: Long? = null, val lastSuccessAt: Long? = null, val nextEligibleAt: Long? = null,
  val generation: String = "", val jobId: String? = null, val slot: Long = 0, val workId: String? = null,
  val operations: Int = 0,
)

/** Scheduler index only. Room owns checkpoints, evidence and charges. */
@Singleton class ResearchScheduleStore @Inject constructor(@ApplicationContext context: Context) {
  private val store = context.researchSchedule
  val statuses: Flow<List<AreaScheduleStatus>> = store.data.map { prefs ->
    prefs.asMap().values.filterIsInstance<String>().map { researchJson.decodeFromString<AreaScheduleStatus>(it) }
  }
  suspend fun all() = statuses.first()
  suspend fun get(area: String) = all().find { it.area == area }
  suspend fun put(row: AreaScheduleStatus) = store.edit {
    it[stringPreferencesKey(row.area)] = researchJson.encodeToString(AreaScheduleStatus.serializer(), row)
  }
}
