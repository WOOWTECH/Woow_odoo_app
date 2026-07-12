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
import io.woowtech.odoo.data.location.ContextPermissionChecker
import io.woowtech.odoo.data.location.PermissionChecker
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.FcmTokenRepositoryImpl
import io.woowtech.odoo.data.repository.SessionCookieProvider
import io.woowtech.odoo.data.repository.SettingsRepository
import okhttp3.Cookie
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
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
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
        odooClient: OdooJsonRpcClient,
        fcmTokenRepository: dagger.Lazy<FcmTokenRepository>,
    ): AccountRepository {
        return AccountRepository(accountDao, encryptedPrefs, odooClient).also { repo ->
            // C3: Wire the FCM token repository lazily to avoid a circular dependency
            // (AccountRepository ← FcmTokenRepository → AccountDao ← AccountRepository).
            // Using dagger.Lazy defers instantiation until the first access, breaking the cycle.
            repo.fcmTokenRepository = fcmTokenRepository.get()
        }
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        encryptedPrefs: EncryptedPrefs
    ): SettingsRepository {
        return SettingsRepository(encryptedPrefs)
    }

    /**
     * Bridges the OdooJsonRpcClient cookie store to the SessionCookieProvider interface
     * so FcmTokenRepositoryImpl can attach session cookies to its HTTP requests without
     * a direct dependency on the JSON-RPC client.
     */
    @Provides
    @Singleton
    fun provideSessionCookieProvider(odooClient: OdooJsonRpcClient): SessionCookieProvider {
        return object : SessionCookieProvider {
            override fun getCookiesForHost(host: String): List<Cookie> =
                odooClient.getSessionCookies(host)
        }
    }

    /**
     * Binds the production [PermissionChecker] so [LocationPermissionGate] can be
     * tested without touching real [android.content.Context] permission APIs.
     */
    @Provides
    @Singleton
    fun providePermissionChecker(
        @ApplicationContext context: Context,
    ): PermissionChecker = ContextPermissionChecker(context)

    @Provides
    @Singleton
    fun provideFcmTokenRepository(
        encryptedPrefs: EncryptedPrefs,
        accountDao: AccountDao,
        sessionCookieProvider: SessionCookieProvider,
    ): FcmTokenRepository {
        return FcmTokenRepositoryImpl(
            encryptedPrefs = encryptedPrefs,
            accountDao = accountDao,
            sessionCookieProvider = sessionCookieProvider,
        )
    }
}
