package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.*
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.researchPlans by preferencesDataStore("aria_research_plans")
@Serializable data class AreaReadPlan(val area: String, val revision: String = UUID.randomUUID().toString(),
  val reads: List<ResearchRead>, val provider: String, val model: String, val effort: String? = null,
  val helperProvider: String? = null, val helperModel: String? = null, val profilingConsent: Boolean = false, val maxRecords: Int = 100) {
  fun policy() = ModelPolicy(ExactModel(ProviderId.valueOf(provider), model), effort?.let { ThinkingLevel.valueOf(it) },
    helperModel?.let { ExactModel(ProviderId.valueOf(requireNotNull(helperProvider)), it) })
}

/** Source scopes are stored independently of area enablement; saving always invalidates active work. */
@Singleton class ResearchSetup @Inject constructor(@ApplicationContext context: Context,
  private val foundation: DurableFoundation, private val budgets: RuntimeBudgetCoordinator) {
  private val store = context.researchPlans
  val areas get() = foundation.settings
  val caps: Flow<List<BudgetCap>> get() = foundation.caps
  val plans: Flow<List<AreaReadPlan>> = store.data.map { prefs -> prefs.asMap().values.filterIsInstance<String>().map { researchJson.decodeFromString<AreaReadPlan>(it) } }
  suspend fun plan(area: String): AreaReadPlan? = store.data.first()[stringPreferencesKey(area)]?.let { researchJson.decodeFromString<AreaReadPlan>(it) }
  suspend fun savePlan(plan: AreaReadPlan) {
    requireResearch(plan.area.isNotBlank() && plan.reads.isNotEmpty() && plan.reads.size <= 32 && plan.model.isNotBlank(), "INVALID_PLAN")
    plan.policy(); plan.reads.forEach { ResearchReadPolicy.check(it); requireResearch(it.selection.area == plan.area, "AREA_SCOPE_MISMATCH") }
    val revised = plan.copy(revision = UUID.randomUUID().toString())
    foundation.changePolicy { store.edit { it[stringPreferencesKey(plan.area)] = researchJson.encodeToString(AreaReadPlan.serializer(), revised) } }
  }
  suspend fun configure(configuration: AreaConfiguration, models: List<AreaModel>) {
    val plan = plan(configuration.area)
    if (configuration.enabled) requireResearch(plan != null && plan.profilingConsent && models.any { it.provider == plan.provider && it.model == plan.model }, "EXPLICIT_PLAN_REQUIRED")
    foundation.changePolicy { foundation.configure(configuration, models) }
  }
  suspend fun configureCap(cap: BudgetCap) = budgets.configure(cap)
  suspend fun configuration(area: String) = foundation.area(area)
  suspend fun models(area: String) = foundation.models(area)
}
