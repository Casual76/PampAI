package dev.pampa.pampai.core.assistant.research

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module @InstallIn(SingletonComponent::class)
abstract class ResearchBindings {
  @Binds abstract fun transport(implementation: SignedResearchTransport): ResearchTransport
  @Binds abstract fun clock(implementation: SystemResearchClock): ResearchClock
  @Binds abstract fun model(implementation: ProviderResearchModel): ResearchModel
}
