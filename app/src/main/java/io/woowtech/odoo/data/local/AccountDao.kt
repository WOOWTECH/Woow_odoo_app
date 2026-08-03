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
     * How many OTHER accounts already own [tenantId].
     *
     * `odoo_tenant_id` defaults to the Odoo database name, and spec §4.3 ships every box with the
     * same `POSTGRES_DB` unless an operator overrides it — so two unrelated servers routinely hand
     * back an identical id. A non-zero count means the id cannot identify an account, and must not
     * be persisted as one (story 8-1, P2-9).
     *
     * ⚠️ Do not add a `SELECT ... WHERE tenantId = :x LIMIT 1` helper back. One existed, had zero
     * callers, and was deleted with this change: its `LIMIT 1` silently picks whichever row the
     * database returns first, which is exactly the non-determinism story 8-1 removed from
     * `DeepLinkRouter`. Resolution must count first and refuse when the count is not one.
     */
    @Query("SELECT COUNT(*) FROM accounts WHERE tenantId = :tenantId AND id != :excludingId")
    suspend fun countAccountsWithTenantId(tenantId: String, excludingId: String): Int

    /**
     * Persists the [tenantId] returned by the Odoo server for the account with [id]. Called
     * after a successful FCM device registration so future notifications can be routed.
     */
    @Query("UPDATE accounts SET tenantId = :tenantId WHERE id = :id")
    suspend fun updateTenantId(id: String, tenantId: String)

    /**
     * Persists the ACCOUNT-scoped push routing key returned by registration (P2-9).
     *
     * ⚠️ The id is unique per `(fcm_token, user_id)` **within one Odoo database** — it is a
     * per-database Postgres sequence. Two identically-deployed boxes both hand out 1, 2, 3,
     * so a collision ACROSS servers is entirely possible and must be logged, exactly as
     * [countAccountsWithTenantId] does for the tenant id.
     */
    @Query("UPDATE accounts SET deviceId = :deviceId WHERE id = :id")
    suspend fun updateDeviceId(id: String, deviceId: String)

    /**
     * How many OTHER accounts already hold [deviceId].
     *
     * Mirrors [countAccountsWithTenantId]. The routing key is only unique within one
     * database, so two servers can issue the same small integer.
     */
    @Query("SELECT COUNT(*) FROM accounts WHERE deviceId = :deviceId AND id != :excludingId")
    suspend fun countAccountsWithDeviceId(deviceId: String, excludingId: String): Int

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
