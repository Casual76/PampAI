package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.work.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.guava.await
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

class ResearchWorker(context: Context, params: WorkerParameters, private val scheduler: Provider<ResearchScheduler>) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val area = inputData.getString("area") ?: return Result.failure()
    val generation = inputData.getString("generation") ?: return Result.failure()
    return try {
      scheduler.get().execute(area, generation)
      Result.success()
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) {
      // Storage can itself be unavailable. Never retry an unknown dispatch through WorkManager.
      try { scheduler.get().recordFailure(area) } catch (_: Exception) { }
      Result.failure()
    }
  }
}

/** Lazy provider breaks Application -> factory -> scheduler -> WorkManager initialization cycle. */
@Singleton class ResearchWorkerFactory @Inject constructor(private val scheduler: Provider<ResearchScheduler>) : WorkerFactory() {
  override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
    if (workerClassName == ResearchWorker::class.java.name) ResearchWorker(appContext, workerParameters, scheduler) else null
}

@Singleton class AndroidResearchWorkQueue @Inject constructor(@ApplicationContext private val context: Context) : ResearchWorkQueue {
  private fun manager() = WorkManager.getInstance(context)
  private fun tag(area: String) = "aria.research.${digest(area)}"
  internal fun constraints(c: dev.pampa.pampai.core.assistant.durable.AreaConfiguration) = Constraints.Builder()
    .setRequiredNetworkType(if (c.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(c.batteryNotLow).build()
  private fun data(p: SchedulePolicy) = workDataOf("area" to p.configuration.area, "generation" to p.generation)
  override suspend fun periodic(policy: SchedulePolicy) {
    val c = policy.configuration
    val request = PeriodicWorkRequestBuilder<ResearchWorker>(c.cadenceMillis, TimeUnit.MILLISECONDS)
      .setInputData(data(policy)).setConstraints(constraints(c)).addTag(tag(c.area)).build()
    manager().enqueueUniquePeriodicWork("${tag(c.area)}.safety", ExistingPeriodicWorkPolicy.UPDATE, request).result.await()
  }
  override suspend fun enqueue(policy: SchedulePolicy, row: AreaScheduleStatus): String {
    val name = "${tag(row.area)}.${row.generation}.${row.slot}"
    val id = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))
    val request = OneTimeWorkRequestBuilder<ResearchWorker>().setId(id).setInputData(data(policy))
      .setInitialDelay(((row.nextEligibleAt ?: 0) - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
      .setConstraints(constraints(policy.configuration)).addTag(tag(row.area)).build()
    manager().enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.await()
    return id.toString()
  }
  override suspend fun cancel(area: String) { manager().cancelAllWorkByTag(tag(area)).result.await() }
  override suspend fun running(workId: String?): Boolean = workId?.let {
    manager().getWorkInfoById(UUID.fromString(it)).await()?.state == WorkInfo.State.RUNNING
  } ?: false
  override suspend fun terminal(workId: String?): Boolean = workId?.let {
    manager().getWorkInfoById(UUID.fromString(it)).await()?.state?.isFinished ?: true
  } ?: true
}

@Module @InstallIn(SingletonComponent::class)
abstract class ResearchSchedulingBindings {
  @Binds abstract fun queue(implementation: AndroidResearchWorkQueue): ResearchWorkQueue
}
