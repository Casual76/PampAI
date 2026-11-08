package dev.pampa.pampai.core.assistant.durable

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "aria_grants")
data class SourceGrant(
  @PrimaryKey val id: String, val app: String, val authority: String, val account: String,
  val student: String?, val area: String, val year: String?, val version: Long = 0,
  val enabled: Boolean = false, val generationProof: String? = null, val proofExpiresAt: Long? = null,
)
@Entity(tableName = "aria_state")
data class LearningState(@PrimaryKey val id: Int = 1, val epoch: Long = 0)
@Entity(tableName = "aria_areas")
data class AreaConfiguration(
  @PrimaryKey val area: String, val enabled: Boolean = false, val setupComplete: Boolean = false,
  val cadenceMillis: Long = 86_400_000, val wifiOnly: Boolean = true, val batteryNotLow: Boolean = true,
  val requestCap: Long = 0, val tokenCap: Long = 0, val moneyCap: Long? = null,
  val windowStart: Long = 0, val windowEnd: Long = 0,
)
@Entity(tableName = "aria_area_models", primaryKeys = ["area", "provider", "model"])
data class AreaModel(val area: String, val provider: String, val model: String)
@Entity(tableName = "aria_jobs", indices = [Index(value = ["state", "eligibleAt"])])
data class ResearchJob(
  @PrimaryKey val id: String, val conversationId: Long?, val area: String, val intent: String,
  val execution: String, val objective: String, val provider: String, val model: String,
  val effort: String?, val helperProvider: String?, val helperModel: String?,
  val maxSteps: Int, val maxRecords: Int, val learningEpoch: Long,
  val state: String = "READY", val eligibleAt: Long = 0, val owner: String? = null,
  val leaseUntil: Long = 0, val fence: Long = 0, val cancelVersion: Long = 0,
)
@Entity(tableName = "aria_job_grants", primaryKeys = ["jobId", "grantId"])
data class JobGrant(val jobId: String, val grantId: String, val version: Long, val generationProof: String?)
@Entity(tableName = "aria_steps", primaryKeys = ["jobId", "ordinal"])
data class ResearchStep(
  val jobId: String, val ordinal: Int, val grantId: String, val query: String,
  val cursor: String? = null, val fragmentOffset: Int = 0, val fragment: String? = null,
  val contentHash: String? = null, val progress: Long = 0, val outcome: String = "UNKNOWN",
)
@Entity(tableName = "aria_evidence", indices = [Index(value = ["grantId", "recordKey", "periodKey"], unique = true), Index("field")])
data class ResearchEvidence(
  @PrimaryKey val id: String, val jobId: String, val grantId: String, val recordKey: String,
  val periodKey: String, val field: String, val value: String, val unit: String?,
  val periodStart: Long?, val periodEnd: Long?, val timeZone: String,
  val observedAt: Long?, val syncedAt: Long?, val coverage: String, val freshness: String,
  val provenance: String, val contentHash: String, val valid: Boolean = true, val valueType: String = "TEXT",
)
@Entity(tableName = "aria_profile", indices = [Index("field"), Index("jobId")])
data class ProfileItem(
  @PrimaryKey val id: String, val field: String, val value: String, val kind: String,
  val jobId: String?, val revision: Long, val status: String = "ACTIVE",
  val generatedProvider: String? = null, val generatedModel: String? = null,
  val derivation: String? = null, val limitations: String? = null, val valueType: String = "TEXT",
)
@Entity(tableName = "aria_dependencies", primaryKeys = ["itemId", "evidenceId"])
data class ProfileDependency(val itemId: String, val evidenceId: String)
@Entity(tableName = "aria_exclusions")
data class ProfileExclusion(@PrimaryKey val key: String, val field: String?, val grantId: String?, val recordKey: String?, val unmapped: Boolean)
@Entity(tableName = "aria_budget_caps")
data class BudgetCap(@PrimaryKey val id: String, val backgroundOnly: Boolean, val start: Long, val end: Long, val requests: Long, val tokens: Long, val money: Long?)
@Entity(tableName = "aria_attempts", indices = [Index("windowAt"), Index("quotaKey")])
data class BudgetAttempt(
  @PrimaryKey val attemptId: String, val executionId: String, val jobId: String?, val area: String,
  val provider: String, val model: String, val purpose: String, val quotaKey: String, val background: Boolean,
  val windowAt: Long, val reservedTokens: Long, val reservedMoney: Long?,
  val chargedRequests: Long = 1, val chargedTokens: Long, val chargedMoney: Long?,
  val state: String = "UNCERTAIN", val settlementKey: String? = null, val error: String? = null,
  val tariffId: String? = null,
)
@Entity(tableName = "aria_cooldowns")
data class ProviderCooldown(@PrimaryKey val quotaKey: String, val eligibleAt: Long, val reason: String)
@Entity(tableName = "aria_foreground")
data class ForegroundActivity(@PrimaryKey val executionId: String)

/** Internal persistence surface. Mutations must go through the transactional services. */
@Dao
interface DurableDao {
  @Query("SELECT * FROM aria_jobs WHERE area=:area AND execution='BACKGROUND' AND state IN ('READY','RUNNING') ORDER BY id LIMIT 1") suspend fun backgroundJob(area: String): ResearchJob?
  @Query("SELECT COUNT(*) FROM aria_attempts WHERE jobId=:job AND state!='NOT_DISPATCHED'") suspend fun dispatchedAttempts(job: String): Int

  @Query("SELECT * FROM aria_budget_caps ORDER BY id") fun capsFlow(): Flow<List<BudgetCap>>
  @Query("SELECT * FROM aria_grants ORDER BY id") fun grantsFlow(): Flow<List<SourceGrant>>
  @Query("SELECT * FROM aria_exclusions ORDER BY `key`") fun exclusionsFlow(): Flow<List<ProfileExclusion>>
  @Query("SELECT * FROM aria_grants ORDER BY id") suspend fun grants(): List<SourceGrant>
  @Query("SELECT * FROM aria_evidence WHERE jobId=:job ORDER BY id") suspend fun jobEvidence(job: String): List<ResearchEvidence>
  @Query("SELECT * FROM aria_jobs WHERE execution='BACKGROUND' AND state IN ('READY','RUNNING') AND eligibleAt<=:now AND (owner IS NULL OR leaseUntil<=:now) AND (eligibleAt>:afterTime OR (eligibleAt=:afterTime AND id>:afterId)) ORDER BY eligibleAt,id LIMIT :limit") suspend fun eligibleBackground(now: Long, afterTime: Long, afterId: String, limit: Int): List<ResearchJob>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun grant(row: SourceGrant)
  @Query("SELECT * FROM aria_grants WHERE id=:id") suspend fun grant(id: String): SourceGrant?
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun state(row: LearningState)
  @Query("SELECT * FROM aria_state WHERE id=1") suspend fun state(): LearningState?
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun area(row: AreaConfiguration)
  @Query("SELECT * FROM aria_areas WHERE area=:area") suspend fun area(area: String): AreaConfiguration?
  @Query("SELECT * FROM aria_areas ORDER BY area") fun areas(): Flow<List<AreaConfiguration>>
  @Insert suspend fun models(rows: List<AreaModel>)
  @Query("DELETE FROM aria_area_models WHERE area=:area") suspend fun clearModels(area: String)
  @Query("SELECT * FROM aria_area_models WHERE area=:area") suspend fun models(area: String): List<AreaModel>
  @Insert suspend fun createJob(row: ResearchJob)
  @Update suspend fun job(row: ResearchJob)
  @Query("SELECT * FROM aria_jobs WHERE id=:id") suspend fun job(id: String): ResearchJob?
  @Query("SELECT * FROM aria_jobs ORDER BY eligibleAt LIMIT 200") fun jobs(): Flow<List<ResearchJob>>
  @Query("UPDATE aria_jobs SET state='CANCELLED', cancelVersion=cancelVersion+1, owner=NULL, leaseUntil=0 WHERE state NOT IN ('DONE','CANCELLED')") suspend fun invalidateJobs()
  @Insert suspend fun vectors(rows: List<JobGrant>)
  @Query("SELECT * FROM aria_job_grants WHERE jobId=:job") suspend fun vectors(job: String): List<JobGrant>
  @Query("UPDATE aria_jobs SET objective='' WHERE state='CANCELLED'") suspend fun scrubCancelledObjectives()
  @Query("DELETE FROM aria_dependencies WHERE itemId=:id") suspend fun clearDependencies(id: String)
  @Query("UPDATE aria_steps SET cursor=NULL, fragment=NULL, contentHash=NULL, fragmentOffset=0, query='', outcome='UNKNOWN'") suspend fun scrubCheckpoints()
  @Query("UPDATE aria_steps SET cursor=NULL, fragment=NULL, contentHash=NULL, fragmentOffset=0, query='', outcome='UNKNOWN' WHERE grantId=:grant") suspend fun scrubGrantCheckpoints(grant: String)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun step(row: ResearchStep)
  @Query("SELECT * FROM aria_steps WHERE jobId=:job ORDER BY ordinal") suspend fun steps(job: String): List<ResearchStep>
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun evidence(row: ResearchEvidence): Long
  @Query("SELECT * FROM aria_evidence WHERE id=:id") suspend fun evidence(id: String): ResearchEvidence?
  @Query("DELETE FROM aria_evidence WHERE grantId=:grant AND recordKey=:record AND periodKey=:period AND valid=0") suspend fun removeInvalidRecord(grant: String, record: String, period: String)
  @Query("SELECT COUNT(*) FROM aria_evidence WHERE jobId=:job") suspend fun evidenceCount(job: String): Int
  @Query("UPDATE aria_evidence SET valid=0 WHERE grantId=:grant") suspend fun invalidateEvidence(grant: String)
  @Query("SELECT * FROM aria_evidence WHERE grantId=:grant AND recordKey=:record AND periodKey=:period") suspend fun record(grant: String, record: String, period: String): ResearchEvidence?
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun profile(row: ProfileItem)
  @Query("SELECT * FROM aria_profile WHERE id=:id") suspend fun profile(id: String): ProfileItem?
  @Query("SELECT * FROM aria_profile WHERE status='ACTIVE' AND field IN (:fields) ORDER BY CASE kind WHEN 'USER_STATEMENT' THEN 0 ELSE 1 END, revision DESC LIMIT :limit") suspend fun candidates(fields: List<String>, limit: Int): List<ProfileItem>
  @Query("SELECT * FROM aria_profile ORDER BY field LIMIT 500") fun profile(): Flow<List<ProfileItem>>
  @Query("UPDATE aria_profile SET status='INVALID' WHERE kind != 'USER_STATEMENT' AND jobId IN (SELECT jobId FROM aria_job_grants WHERE grantId=:grant)") suspend fun invalidateProfile(grant: String)
  @Query("UPDATE aria_profile SET status='SUPERSEDED' WHERE field=:field") suspend fun supersede(field: String)
  @Query("UPDATE aria_profile SET status='FORGOTTEN', value='', limitations=NULL WHERE (:field IS NULL OR field=:field)") suspend fun forgetField(field: String?)
  @Query("UPDATE aria_profile SET status='FORGOTTEN', value='', limitations=NULL WHERE id IN (SELECT itemId FROM aria_dependencies WHERE evidenceId IN (SELECT id FROM aria_evidence WHERE (:field IS NULL OR field=:field) AND (:grant IS NULL OR grantId=:grant) AND (:record IS NULL OR recordKey=:record)))") suspend fun forgetDependents(field: String?, grant: String?, record: String?)
  @Query("DELETE FROM aria_evidence WHERE (:field IS NULL OR field=:field) AND (:grant IS NULL OR grantId=:grant) AND (:record IS NULL OR recordKey=:record)") suspend fun forgetEvidence(field: String?, grant: String?, record: String?)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun dependency(row: ProfileDependency)
  @Query("SELECT * FROM aria_dependencies WHERE itemId=:item") suspend fun dependencies(item: String): List<ProfileDependency>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exclusion(row: ProfileExclusion)
  @Query("SELECT * FROM aria_exclusions") suspend fun exclusions(): List<ProfileExclusion>
  @Query("DELETE FROM aria_exclusions WHERE `key`=:key") suspend fun reenable(key: String)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cap(row: BudgetCap)
  @Query("SELECT * FROM aria_budget_caps") suspend fun caps(): List<BudgetCap>
  @Insert suspend fun reserve(row: BudgetAttempt)
  @Update suspend fun attempt(row: BudgetAttempt)
  @Query("SELECT * FROM aria_attempts WHERE attemptId=:id") suspend fun attempt(id: String): BudgetAttempt?
  @Query("SELECT * FROM aria_attempts WHERE windowAt>=:start AND windowAt<:end") suspend fun attempts(start: Long, end: Long): List<BudgetAttempt>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cooldown(row: ProviderCooldown)
  @Query("SELECT * FROM aria_cooldowns WHERE quotaKey=:key") suspend fun cooldown(key: String): ProviderCooldown?
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun foreground(row: ForegroundActivity)
  @Query("DELETE FROM aria_foreground WHERE executionId=:id") suspend fun endForeground(id: String)
  @Query("SELECT executionId FROM aria_foreground") suspend fun foregroundExecutions(): List<String>
  @Query("SELECT COUNT(*) FROM aria_foreground") suspend fun foregroundCount(): Int
  @Query("SELECT COUNT(*) FROM conversations WHERE id=:id AND `temporary`=0") suspend fun durableConversation(id: Long): Int
}
