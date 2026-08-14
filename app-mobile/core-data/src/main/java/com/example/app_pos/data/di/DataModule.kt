package com.example.app_pos.data.di

import android.content.Context
import androidx.room.Room
import com.example.app_pos.data.OfflineFirstRepository
import com.example.app_pos.data.SeedCallback
import com.example.app_pos.data.db.AppDatabase
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.local.RoomLocalDataSource
import com.example.app_pos.model.Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The data layer's object graph: one database, one repository, both singletons — a second
 * AppDatabase over the same file would give two write queues and two caches of the same rows.
 *
 * Replaces the hand-rolled RepositoryProvider. The provider worked while there was exactly
 * one dependency to hand out; the network layer adds a TokenStore, a RemoteDataSource and a
 * Moshi instance that all have to reach the same objects, and wiring those by hand is where
 * a second instance quietly appears.
 *
 * Screens depend on the [Repository] interface, never on a concrete class, so the local /
 * remote split stays an implementation detail of this module. This binding is the single
 * place that decides which implementation the whole app gets — which is what let the
 * offline-first composition land here without changing a single caller.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "veresiye.db")
            // Mock phase: if the entity set changes, wipe rather than migrate. Real
            // migrations arrive with the backend phase (schemas are exported for diffing).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(SeedCallback(context))
            .build()

    /**
     * On-device storage, behind its interface so the session logic above it can be tested
     * without standing up Room.
     */
    @Provides
    @Singleton
    fun provideLocalSource(db: AppDatabase): LocalSource = RoomLocalDataSource(db)

    /**
     * The app-wide Repository: local storage, the backend and the persisted session
     * composed into one surface. Swapping implementations is a one-line change here.
     */
    @Provides
    @Singleton
    fun provideRepository(impl: OfflineFirstRepository): Repository = impl
}
