package dev.pampa.pampai.feature.assistant.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.usage.UsageEvent
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Il periodo del tracker. */
enum class UsagePeriod(val label: String, val days: Int) { DAY("Oggi", 1), WEEK("7 giorni", 7), MONTH("30 giorni", 30) }

data class ModelUsage(val model: String, val kind: String, val requests: Int, val tokens: Int, val costUsd: Double, val audioSeconds: Double, val errors: Int)

data class ProviderUsage(
  val provider: ProviderId,
  val requests: Int,
  val tokens: Int,
  val costUsd: Double,
  val rateLimited: Int,
  val errors: Int,
  val remainingRequests: Int?,
  val remainingTokens: Int?,
  val models: List<ModelUsage>,
)

data class UsageSummary(
  val period: UsagePeriod = UsagePeriod.DAY,
  val requests: Int = 0,
  val tokens: Int = 0,
  val costUsd: Double = 0.0,
  val rateLimited: Int = 0,
  val providers: List<ProviderUsage> = emptyList(),
  /** I token per intervallo (ore del giorno, o giorni), per il grafico. */
  val series: List<Float> = emptyList(),
  val seriesLabel: String = "",
  /** Le richieste a Gemini oggi: il free tier non manda i limiti negli header, si contano qui. */
  val geminiToday: Int = 0,
)

/**
 * Il tracker dei consumi: gli eventi del periodo, aggregati per servizio e modello, piu' una serie
 * per il grafico. Gli avvisi (limiti, 429) li deduce la schermata dai numeri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(private val usage: UsageRepository) : ViewModel() {

  val period = MutableStateFlow(UsagePeriod.DAY)

  private val zone: ZoneId = ZoneId.systemDefault()

  private fun start(period: UsagePeriod): ZonedDateTime = ZonedDateTime.now(zone).toLocalDate().minusDays((period.days - 1).toLong()).atStartOfDay(zone)

  private val events = period.flatMapLatest { p -> usage.observeSince(start(p).toInstant().toEpochMilli()).map { p to it } }

  val summary: StateFlow<UsageSummary> = combine(events, usage.observeSince(ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli())) { (p, list), today ->
    summarize(p, list, today.count { it.provider == ProviderId.GEMINI })
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageSummary())

  fun setPeriod(p: UsagePeriod) {
    period.value = p
  }

  fun clear() = viewModelScope.launch { usage.clear() }

  private fun summarize(period: UsagePeriod, list: List<UsageEvent>, geminiToday: Int): UsageSummary {
    val providers = list.groupBy { it.provider }.map { (provider, events) ->
      val latestLimit = events.maxByOrNull { it.atMillis }?.takeIf { it.remainingRequests != null }
      ProviderUsage(
        provider = provider,
        requests = events.size,
        tokens = events.sumOf { it.tokens },
        costUsd = events.sumOf { it.costUsd ?: 0.0 },
        rateLimited = events.count { it.rateLimited },
        errors = events.count { it.error != null && !it.rateLimited },
        remainingRequests = latestLimit?.remainingRequests,
        remainingTokens = latestLimit?.remainingTokens,
        models = events.groupBy { it.model to it.kind }.map { (key, byModel) ->
          ModelUsage(key.first, key.second, byModel.size, byModel.sumOf { it.tokens }, byModel.sumOf { it.costUsd ?: 0.0 }, byModel.sumOf { it.audioSeconds ?: 0.0 }, byModel.count { it.error != null })
        }.sortedByDescending { it.requests },
      )
    }.sortedByDescending { it.requests }
    val from = start(period)
    val buckets = if (period == UsagePeriod.DAY) 24 else period.days
    val series = FloatArray(buckets)
    list.forEach { event ->
      val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.atMillis), zone)
      val index = if (period == UsagePeriod.DAY) at.hour else java.time.temporal.ChronoUnit.DAYS.between(from.toLocalDate(), at.toLocalDate()).toInt()
      if (index in 0 until buckets) series[index] += event.tokens.toFloat()
    }
    return UsageSummary(
      period = period,
      requests = list.size,
      tokens = list.sumOf { it.tokens },
      costUsd = list.sumOf { it.costUsd ?: 0.0 },
      rateLimited = list.count { it.rateLimited },
      providers = providers,
      series = series.toList(),
      seriesLabel = if (period == UsagePeriod.DAY) "token per ora" else "token per giorno",
      geminiToday = geminiToday,
    )
  }
}
