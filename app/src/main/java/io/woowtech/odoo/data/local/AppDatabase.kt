package io.woowtech.odoo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.woowtech.odoo.domain.model.OdooAccount

@Database(
    entities = [OdooAccount::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    companion object {
        const val DATABASE_NAME = "woowtech_odoo_db"

        /**
         * Adds the [OdooAccount.tenantId] column introduced for multi-account push-notification
         * deep-link routing. The column is nullable so existing rows migrate cleanly — accounts
         * repopulate their tenant id on the next successful FCM device registration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN tenantId TEXT")
            }
        }

        /**
         * Adds [OdooAccount.deviceId] — the ACCOUNT-scoped push routing key (P2-9 root cause).
         *
         * `tenantId` cannot identify an account. The server resolves it to the Odoo database
         * name, so two users on ONE database necessarily share it, and every STB box ships
         * with the same `POSTGRES_DB` so two unrelated servers collide too. Story 8-1 made the
         * router REFUSE an ambiguous tenant id rather than guess — safe, but it costs those
         * users their deep links entirely.
         *
         * The server now stamps the `woow.fcm.device` row id on every push, and returns that
         * same id from registration, so an account can be identified by a value that is unique
         * per (token, user) by construction.
         *
         * Nullable, like `tenantId`: existing rows migrate cleanly and repopulate on the next
         * successful FCM registration. Until then the router falls back to the tenant id, so
         * this is additive rather than a flag day.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN deviceId TEXT")
            }
        }
    }
}
