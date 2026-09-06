package dev.pampa.pampai.core.assistant.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.pampai.core.assistant.prompt.PreRouter
import javax.inject.Singleton

/** Il pre-router locale: una tabella di parole, senza dipendenze. Le app collegate aggiungeranno le loro regole. */
@Module
@InstallIn(SingletonComponent::class)
object RoutingModule {

  @Provides
  @Singleton
  fun providePreRouter(): PreRouter = PreRouter()
}
