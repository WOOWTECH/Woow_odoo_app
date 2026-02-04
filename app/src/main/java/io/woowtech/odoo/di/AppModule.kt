package io.woowtech.odoo.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.AppDatabase
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    @Singleton
    fun provideAccountDao(database: AppDatabase): AccountDao {
        return database.accountDao()
    }

    @Provides
    @Singleton
    fun provideEncryptedPrefs(
        @ApplicationContext context: Context
    ): EncryptedPrefs {
        return EncryptedPrefs(context)
    }

    @Provides
    @Singleton
    fun provideOdooJsonRpcClient(): OdooJsonRpcClient {
        return OdooJsonRpcClient()
    }

    @Provides
    @Singleton
    fun provideAccountRepository(
        accountDao: AccountDao,
        encryptedPrefs: EncryptedPrefs,
        odooClient: OdooJsonRpcClient
    ): AccountRepository {
        return AccountRepository(accountDao, encryptedPrefs, odooClient)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        encryptedPrefs: EncryptedPrefs
    ): SettingsRepository {
        return SettingsRepository(encryptedPrefs)
    }
}
