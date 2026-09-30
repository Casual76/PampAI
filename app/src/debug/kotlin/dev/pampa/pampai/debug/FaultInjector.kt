package dev.pampa.pampai.debug

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.pampa.pampai.core.assistant.debug.ProviderDecorator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'iniettore di guasti della build di debug: il [ProviderDecorator] che `ProviderFactory` mette
 * attorno a ogni client. Si comanda da adb con [FaultReceiver].
 *
 * Avvolge un provider solo se per lui c'e' un guasto armato quando viene costruito; altrimenti lo
 * restituisce identico, e la build di debug si comporta come la release (anche i crediti di
 * OpenRouter nelle impostazioni, che un involucro nasconderebbe). I provider si costruiscono a ogni
 * domanda: un guasto armato vale dalla domanda dopo.
 */
@Singleton
class FaultInjector internal constructor(private val log: (String) -> Unit) : ProviderDecorator {

  @Inject
  constructor() : this({ Log.w(TAG, it) })

  private val plan = FaultPlan()

  override fun decorate(provider: ChatProvider): ChatProvider =
    if (plan.isArmed(provider.id)) FaultyProvider(provider, plan, log) else provider

  /** Esegue un comando e dice, in una riga, cosa e' armato adesso (torna ad adb come dato del broadcast). */
  fun apply(command: FaultCommand): String {
    val summary = when (command) {
      is FaultCommand.Invalid -> "comando ignorato: ${command.reason}"
      FaultCommand.Off -> {
        plan.clear()
        "tutti i guasti spenti"
      }
      is FaultCommand.Arm -> {
        plan.arm(command)
        "armato ${command.kind.wire} x${command.count} su ${command.providers.joinToString { it.id }} (chiamate: ${command.target.wire}); " +
          "vale dalla prossima domanda"
      }
    }
    log("$summary | stato: ${state()}")
    return summary
  }

  fun state(): String = plan.snapshot().entries.joinToString(prefix = "[", postfix = "]") { (provider, armed) ->
    "${provider.id}=${armed.kind.wire} x${armed.remaining} ${armed.target.wire}"
  }

  companion object {
    const val TAG = "FaultInjector"
  }
}

/** Solo nel source set di debug: e' questo `@Binds` a riempire l'`Optional<ProviderDecorator>` di `AssistantModule`. */
@Module
@InstallIn(SingletonComponent::class)
interface FaultInjectionModule {
  @Binds
  fun decorator(injector: FaultInjector): ProviderDecorator
}

/** Il receiver non e' iniettato da Hilt: prende l'iniettore da qui. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaultEntryPoint {
  fun faultInjector(): FaultInjector
}
