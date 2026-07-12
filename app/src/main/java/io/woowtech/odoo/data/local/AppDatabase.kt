package io.woowtech.odoo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.woowtech.odoo.domain.model.OdooAccount

@Database(
    entities = [OdooAccount::class],
    version = 2,
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
    }
}
