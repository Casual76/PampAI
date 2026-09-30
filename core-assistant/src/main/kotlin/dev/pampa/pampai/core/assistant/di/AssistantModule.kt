package dev.pampa.pampai.core.assistant.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampai.core.assistant.debug.ProviderDecorator
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import java.io.File
import java.util.Optional
import javax.inject.Singleton

/** L'engine-ai cablato in Hilt: tutto singleton, tutto pigro. Il dominio di Aria sta altrove. */
@Module
@InstallIn(SingletonComponent::class)
object AssistantModule {

  @Provides
  @Singleton
  fun provideAiHttp(@ApplicationContext context: Context): AiHttp {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    // Letture larghe: una risposta lunga sul modello profondo puo' tacere per un pezzo.
    return AiHttp(userAgent = "PampAI/$version", readTimeoutMillis = 120_000, streamChunkTimeoutMillis = 45_000)
  }

  @Provides
  @Singleton
  fun provideAiKeyStore(@ApplicationContext context: Context): AiKeyStore = AiKeyStore(context)

  @Provides
  @Singleton
  fun provideAiSettingsStore(@ApplicationContext context: Context): AiSettingsStore = AiSettingsStore(context)

  @Provides
  @Singleton
  fun providePampaiSettingsStore(@ApplicationContext context: Context): PampaiSettingsStore = PampaiSettingsStore(context)

  @Provides
  @Singleton
  fun provideEngineSettingsStore(@ApplicationContext context: Context): EngineSettingsStore = EngineSettingsStore(context)

  @Provides
  @Singleton
  fun provideModelCatalogStore(@ApplicationContext context: Context): ModelCatalogStore = ModelCatalogStore(File(context.filesDir, "ai/models"))

  /**
   * [decorator] c'e' solo nella build di debug (l'iniettore di guasti, `app/src/debug`); in release
   * l'`Optional` e' vuoto e `ProviderFactory` riceve l'identita', cioe' il suo default.
   */
  @Provides
  @Singleton
  fun provideProviderFactory(
    http: AiHttp,
    keys: AiKeyStore,
    settings: AiSettingsStore,
    catalogs: ModelCatalogStore,
    decorator: Optional<ProviderDecorator>,
  ): ProviderFactory {
    val decorate: (ChatProvider) -> ChatProvider = if (decorator.isPresent) decorator.get()::decorate else { provider -> provider }
    return ProviderFactory(
      http = http,
      keys = keys,
      settings = settings,
      referer = "https://github.com/Casual76/PampAI",
      appTitle = "PampAI",
      catalogs = catalogs,
      decorate = decorate,
    )
  }

  @Provides
  @Singleton
  fun provideAiKeyVerifier(keys: AiKeyStore, settings: AiSettingsStore, providers: ProviderFactory, catalogs: ModelCatalogStore): AiKeyVerifier =
    AiKeyVerifier(keys, settings, providers, catalogs)

  @Provides
  @Singleton
  fun provideDiagnostics(): AiDiagnosticsLog = AiDiagnosticsLog(capacity = 30)

  @Provides
  @Singleton
  fun provideConfirmationGate(): AiConfirmationGate = AiConfirmationGate()
}
