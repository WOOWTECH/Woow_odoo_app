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

    @Query("SELECT * FROM accounts ORDER BY lastLogin DESC")
    suspend fun getAllAccountsList(): List<OdooAccount>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: String): OdooAccount?

    @Query("SELECT * FROM accounts WHERE serverUrl = :serverUrl AND database = :database AND username = :username LIMIT 1")
    suspend fun findAccount(serverUrl: String, database: String, username: String): OdooAccount?

    /**
     * Resolves the local account that owns the given opaque [tenantId], **only when exactly one
     * account carries it**. Returns null when no account has registered that tenant id yet *and*
     * when two or more accounts share it — both are "unresolved" as far as routing is concerned,
     * and the deep-link router drops the notification rather than mis-routing it to the active
     * account.
     *
     * The `COUNT(*) = 1` sub-select is the refusal, expressed in SQL. The previous `LIMIT 1`
     * silently collapsed an ambiguous tenant id into whichever row SQLite happened to return
     * first — the "take a first match" behaviour the server plugin's `notification_targeting.py`
     * contract explicitly forbids ("Clients must COUNT and refuse an ambiguous value rather than
     * take a first match"). `tenantId` has no UNIQUE constraint (see [AppDatabase.MIGRATION_1_2]),
     * so duplicates are reachable in the real schema.
     */
    @Query(
        "SELECT * FROM accounts WHERE tenantId = :tenantId " +
            "AND (SELECT COUNT(*) FROM accounts WHERE tenantId = :tenantId) = 1"
    )
    suspend fun getAccountByTenantId(tenantId: String): OdooAccount?

    /**
     * Number of local accounts carrying [tenantId]. Anything other than 1 means the tenant id
     * cannot be resolved to a single account; callers must refuse rather than pick one.
     */
    @Query("SELECT COUNT(*) FROM accounts WHERE tenantId = :tenantId")
    suspend fun countAccountsByTenantId(tenantId: String): Int

    /**
     * Persists the [tenantId] returned by the Odoo server for the account with [id]. Called
     * after a successful FCM device registration so future notifications can be routed.
     */
    @Query("UPDATE accounts SET tenantId = :tenantId WHERE id = :id")
    suspend fun updateTenantId(id: String, tenantId: String)

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
