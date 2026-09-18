package io.woowtech.odoo.data.local

import android.app.Application
import androidx.room.Room
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * EP-08F-Android: the DAO half of "COUNT and refuse an ambiguous tenant id".
 *
 * `getAccountByTenantId` used to be `SELECT * FROM accounts WHERE tenantId = :tenantId LIMIT 1`.
 * `LIMIT 1` makes an ambiguous tenant id *look* resolvable: two accounts carrying the same value
 * silently collapse into whichever row SQLite returns first, which is exactly the "first match"
 * the server plugin's contract forbids. It also makes the ambiguity undetectable by any caller.
 *
 * `tenantId` has no UNIQUE constraint (see [AppDatabase.MIGRATION_1_2] — a plain nullable TEXT
 * column), so duplicates are reachable in the real schema, not a hypothetical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountDaoAmbiguousTenantTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AccountDao

    private fun account(id: String, tenantId: String?) = OdooAccount(
        id = id,
        serverUrl = "$id-odoo.woowtech.io",
        database = id,
        username = "$id@woowtest.invalid",
        displayName = id,
        tenantId = tenantId,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.accountDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `Given two accounts sharing a tenant id when resolving then returns null instead of the first row`() =
        runBlocking {
            dao.insertAccount(account("acc-a", "tenant-DUP"))
            dao.insertAccount(account("acc-b", "tenant-DUP"))

            assertNull(dao.getAccountByTenantId("tenant-DUP"))
        }

    @Test
    fun `Given exactly one account with a tenant id when resolving then returns that account`() =
        runBlocking {
            dao.insertAccount(account("acc-a", "tenant-DUP"))
            dao.insertAccount(account("acc-b", "tenant-DUP"))
            dao.insertAccount(account("acc-c", "tenant-UNIQUE"))

            assertEquals("acc-c", dao.getAccountByTenantId("tenant-UNIQUE")?.id)
        }

    @Test
    fun `Given no account with the tenant id when resolving then returns null`() = runBlocking {
        dao.insertAccount(account("acc-a", "tenant-DUP"))

        assertNull(dao.getAccountByTenantId("tenant-MISSING"))
    }

    @Test
    fun `Given accounts with null tenant ids when resolving a real tenant id then returns null`() =
        runBlocking {
            // Several rows may legitimately have tenantId = NULL (never registered). NULL = NULL is
            // never true in SQL, so these must neither match nor inflate the ambiguity count.
            dao.insertAccount(account("acc-a", null))
            dao.insertAccount(account("acc-b", null))
            dao.insertAccount(account("acc-c", "tenant-UNIQUE"))

            assertNull(dao.getAccountByTenantId("tenant-MISSING"))
            assertEquals("acc-c", dao.getAccountByTenantId("tenant-UNIQUE")?.id)
        }

    @Test
    fun `Given duplicates when counting then reports the real number of matches`() = runBlocking {
        dao.insertAccount(account("acc-a", "tenant-DUP"))
        dao.insertAccount(account("acc-b", "tenant-DUP"))
        dao.insertAccount(account("acc-c", "tenant-UNIQUE"))

        assertEquals(2, dao.countAccountsByTenantId("tenant-DUP"))
        assertEquals(1, dao.countAccountsByTenantId("tenant-UNIQUE"))
        assertEquals(0, dao.countAccountsByTenantId("tenant-MISSING"))
    }
}
