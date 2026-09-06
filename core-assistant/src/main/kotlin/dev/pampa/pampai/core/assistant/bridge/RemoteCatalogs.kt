package dev.pampa.pampai.core.assistant.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.bridge.AiToolClient
import dev.antigravity.fluidengine.ai.bridge.RemoteAvailability
import dev.antigravity.fluidengine.ai.bridge.RemoteToolHost
import dev.antigravity.fluidengine.ai.bridge.RemoteToolSet
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.pampa.pampai.core.assistant.tools.ACTIONS_OFF
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Un'app Pampa vista dal bridge: c'e', risponde, quanti tool porta. */
data class ConnectedApp(
  val packageName: String,
  val label: String,
  val domain: String,
  val version: String,
  val availability: RemoteAvailability,
  val toolCount: Int,
  val hint: String?,
)

/**
 * Le app collegate: all'avvio (e a ogni app installata, aggiornata o rimossa) si cercano i
 * provider con il meta-data del bridge, si leggono i cataloghi e si montano nel registry di Aria.
 * Niente cache su disco: un catalogo e' una chiamata Binder locale, e un catalogo vecchio dopo un
 * aggiornamento dell'app sarebbe peggio di nessuno.
 */
@Singleton
class RemoteCatalogs @Inject constructor(
  @ApplicationContext private val context: Context,
  private val registryHolder: RegistryHolder,
) {

  private val client = AiToolClient(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Mutex()
  private val stateFlow = MutableStateFlow<List<ConnectedApp>>(emptyList())
  val state: StateFlow<List<ConnectedApp>> = stateFlow

  private val refreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = refreshing

  /** Come i tool remoti chiedono conferma, parlano e rispettano l'interruttore delle azioni: con il contesto di Aria. */
  private val host = object : RemoteToolHost<PampaiToolContext> {
    override suspend fun confirm(ctx: PampaiToolContext, toolName: String, text: ConfirmationText): ToolOutput? = ctx.confirm(toolName, text.title, text.detail)
    override fun language(ctx: PampaiToolContext): String = ctx.language
    override fun actionsEnabled(ctx: PampaiToolContext): Boolean = ctx.actionsEnabled
    override val actionsOffText: String get() = ACTIONS_OFF
  }

  private var started = false

  /** Parte una volta per processo: il primo giro e il ricevitore dei pacchetti. */
  fun start() {
    if (started) return
    started = true
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addDataScheme("package")
    }
    runCatching {
      context.registerReceiver(
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            refreshAsync()
          }
        },
        filter,
      )
    }
    refreshAsync()
  }

  fun refreshAsync() {
    scope.launch { runCatching { refresh() } }
  }

  suspend fun refresh() = lock.withLock {
    refreshing.value = true
    try {
      val hosts = client.discover().filter { it.packageName != context.packageName }
      val sets = mutableListOf<RemoteToolSet<PampaiToolContext>>()
      val apps = hosts.map { host ->
        val availability = client.availability(host)
        val catalog = if (availability == RemoteAvailability.NO_PERMISSION || availability == RemoteAvailability.NOT_INSTALLED) null else client.catalog(host)
        if (catalog != null) sets += RemoteToolSet.of(client, catalog, this.host)
        ConnectedApp(
          packageName = host.packageName,
          label = catalog?.appLabel ?: host.label,
          domain = catalog?.domain ?: host.packageName.substringAfterLast('.'),
          version = catalog?.appVersion ?: "",
          availability = availability,
          toolCount = catalog?.tools?.size ?: 0,
          hint = catalog?.hint,
        )
      }
      registryHolder.setRemote(sets)
      stateFlow.value = apps.sortedBy { it.label }
    } finally {
      refreshing.value = false
    }
  }
}
