package br.com.ppci.levantamento.di

import android.content.Context
import androidx.room.Room
import br.com.ppci.levantamento.data.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "ppci.db").build()

    @Provides fun surveyDao(db: AppDatabase) = db.surveyDao()
    @Provides fun markerDao(db: AppDatabase) = db.markerDao()
}
