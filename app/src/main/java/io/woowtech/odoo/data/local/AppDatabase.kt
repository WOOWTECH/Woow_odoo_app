package io.woowtech.odoo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.woowtech.odoo.domain.model.OdooAccount

@Database(
    entities = [OdooAccount::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    companion object {
        const val DATABASE_NAME = "woowtech_odoo_db"
    }
}
