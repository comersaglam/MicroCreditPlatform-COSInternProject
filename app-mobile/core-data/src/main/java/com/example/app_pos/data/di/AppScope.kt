package com.example.app_pos.data.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A scope tied to the process, not to any screen.
 *
 * For work that must finish even though the screen that started it is gone. The motivating
 * case is the outbox push after a sale: closing the sale flow clears its ViewModel and
 * cancels viewModelScope, so a push launched there would be cut off mid-request.
 *
 * Not a general-purpose escape hatch — anything a screen displays belongs in viewModelScope,
 * where it is cancelled when nobody is looking.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        // SupervisorJob: one failed job must not tear down the scope and silently disable
        // every later sync.
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

/** The dispatcher for disk and network work. Injected so tests can substitute one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
