package com.example.app_pos.data.di

import android.content.Context
import androidx.room.Room
import com.example.app_pos.data.OfflineFirstRepository
import com.example.app_pos.data.db.AppDatabase
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.local.RoomLocalDataSource
import com.example.app_pos.model.PgwDispatcher
import com.example.app_pos.model.Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The data layer's object graph: one database, one local source, one repository, all
 * singletons — a second AppDatabase over the same file would give two write queues and
 * two caches of the same rows.
 *
 * Screens depend on the [Repository] interface, never on a concrete class, so the local /
 * remote / offline-first split stays an implementation detail of this module. This binding
 * is the single place that decides which implementation the whole app gets.
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
            // No seed callback any more (Turn 39). The device used to write its own demo
            // rows on first launch, which made the screen a mixture of local fiction and
            // server truth. Demo data now lives in one place, `backend/app/seed.py`, and
            // reaches the device the same way real data does. Reset it with:
            //   docker compose exec api python -m app.reset
            .build()

    @Provides
    @Singleton
    fun provideLocalSource(db: AppDatabase): LocalSource = RoomLocalDataSource(db)

    /** The app-wide Repository. Swapping implementations is a one-line change here. */
    @Provides
    @Singleton
    fun provideRepository(impl: OfflineFirstRepository): Repository = impl

    /**
     * Gateway dispatch, bound separately from [Repository] even though one class serves
     * both.
     *
     * Kept apart because the two are consumed by different things for different reasons:
     * every screen needs the Repository, while only the component that fires gateway
     * intents needs this. Folding these three methods into Repository would put them on
     * the surface of eight ViewModels that must never call them.
     */
    @Provides
    @Singleton
    fun providePgwDispatcher(impl: OfflineFirstRepository): PgwDispatcher = impl
}
