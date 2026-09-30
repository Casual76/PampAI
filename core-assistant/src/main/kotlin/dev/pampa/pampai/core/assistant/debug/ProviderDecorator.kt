package dev.pampa.pampai.core.assistant.debug

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ai.provider.ChatProvider

/**
 * Un involucro attorno a ogni client IA che `ProviderFactory` costruisce (engine 2.8.0, parametro
 * `decorate`). Esiste per le build di debug, che ci mettono l'iniettore di guasti per provare sul
 * telefono vero il failover e il foglio "Dettagli" senza aspettare che un provider sbagli davvero.
 *
 * Nel `main` non c'e' nessuna implementazione: la release non ne ha, e `ProviderFactory` riceve il
 * suo default, l'identita'. Zero cambiamenti di comportamento.
 */
fun interface ProviderDecorator {
  fun decorate(provider: ChatProvider): ChatProvider
}

/**
 * Il [ProviderDecorator] e' facoltativo: chi lo vuole (la build di debug) lo lega con un `@Binds`
 * suo, gli altri trovano un `Optional` vuoto.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ProviderDecoratorModule {
  @BindsOptionalOf
  fun optionalDecorator(): ProviderDecorator
}
