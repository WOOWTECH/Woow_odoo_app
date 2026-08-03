package io.woowtech.odoo.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.api.SessionReauthInterceptor
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.AppDatabase
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.location.ContextPermissionChecker
import io.woowtech.odoo.data.location.PermissionChecker
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.FcmTokenRepositoryImpl
import io.woowtech.odoo.data.repository.ReloginSignal
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
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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
            override fun getCookiesForAccount(accountId: String): List<Cookie> =
                odooClient.getSessionCookies(accountId)
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

    /**
     * Provides the guardrail'd [SessionReauthenticator] re-auth engine (WI-3). It is invoked by
     * [SessionReauthInterceptor] once an expired Odoo session is detected on the FCM register/unregister
     * responses. Exposed as a singleton so any manual re-login handler can share the same
     * circuit-breaker state via [SessionReauthenticator.onManualReloginSucceeded].
     */
    @Provides
    @Singleton
    fun provideSessionReauthenticator(
        odooClient: OdooJsonRpcClient,
        accountDao: AccountDao,
        encryptedPrefs: EncryptedPrefs,
        reloginSignal: ReloginSignal,
    ): SessionReauthenticator {
        return SessionReauthenticator(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
            reloginSignal = reloginSignal,
        )
    }

    /**
     * Provides the OkHttp [SessionReauthInterceptor] that detects an expired Odoo session — whether
     * signalled as an HTTP 200 JSON-RPC `SessionExpiredException` envelope (the real Odoo `type='json'`
     * contract) or a transport-level 401 — and drives a single guardrail'd re-auth + retry via
     * [SessionReauthenticator]. Replaces the previous [okhttp3.Authenticator] wiring, which never fired
     * because Odoo returns session expiry as HTTP 200.
     */
    @Provides
    @Singleton
    fun provideSessionReauthInterceptor(
        reauthenticator: SessionReauthenticator,
        sessionCookieProvider: SessionCookieProvider,
    ): SessionReauthInterceptor {
        // The cookie provider is required for the REPLAY: the FCM client carries no CookieJar
        // (story 8-2, P0-3), so without it a replayed request would re-present the stale cookie
        // and the re-authentication would accomplish nothing.
        return SessionReauthInterceptor(
            reauthenticator = reauthenticator,
            cookieProvider = sessionCookieProvider,
        )
    }

    @Provides
    @Singleton
    fun provideFcmTokenRepository(
        encryptedPrefs: EncryptedPrefs,
        accountDao: AccountDao,
        sessionCookieProvider: SessionCookieProvider,
        sessionReauthInterceptor: SessionReauthInterceptor,
    ): FcmTokenRepository {
        return FcmTokenRepositoryImpl(
            encryptedPrefs = encryptedPrefs,
            accountDao = accountDao,
            sessionCookieProvider = sessionCookieProvider,
            sessionReauthInterceptor = sessionReauthInterceptor,
        )
    }
}
