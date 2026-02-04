package io.woowtech.odoo.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY lastLogin DESC")
    fun getAllAccounts(): Flow<List<OdooAccount>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccount(): Flow<OdooAccount?>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccountOnce(): OdooAccount?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: String): OdooAccount?

    @Query("SELECT * FROM accounts WHERE serverUrl = :serverUrl AND database = :database AND username = :username LIMIT 1")
    suspend fun findAccount(serverUrl: String, database: String, username: String): OdooAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: OdooAccount)

    @Update
    suspend fun updateAccount(account: OdooAccount)

    @Delete
    suspend fun deleteAccount(account: OdooAccount)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE accounts SET isActive = 1 WHERE id = :id")
    suspend fun activateAccount(id: String)

    @Query("UPDATE accounts SET lastLogin = :timestamp WHERE id = :id")
    suspend fun updateLastLogin(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int
}
