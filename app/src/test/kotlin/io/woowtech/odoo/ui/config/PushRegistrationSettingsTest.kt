package io.woowtech.odoo.ui.config

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.*
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Phase 3: Settings must select observations by account ID, not retain another account's ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() = Dispatchers.setMain(dispatcher)
    @AfterEach fun teardown() = Dispatchers.resetMain()

    @Test
    fun `Given A acknowledged and B unconfigured when active account switches then Settings follows B`() = runTest {
        val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A")
        val b = a.copy(id = "b", database = "db-b")
        val active = MutableStateFlow<OdooAccount?>(a)
        val statuses = MutableStateFlow(mapOf("a" to PushRegistrationStatus.ACKNOWLEDGED,
            "b" to PushRegistrationStatus.NOT_CONFIGURED))
        val accounts = mockk<AccountRepository>(relaxed = true)
        val fcm = mockk<FcmTokenRepository>(relaxed = true)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { accounts.activeAccount } returns active
        every { fcm.registrationStatuses } returns statuses
        every { settings.settings } returns MutableStateFlow(AppSettings())
        val vm = SettingsViewModel(settings, mockk(relaxed = true), accounts, fcm)
        vm.pushRegistrationStatus.test {
            assertEquals(PushRegistrationStatus.NOT_CHECKED, awaitItem())
            assertEquals(PushRegistrationStatus.ACKNOWLEDGED, awaitItem())
            active.value = b
            assertEquals(PushRegistrationStatus.NOT_CONFIGURED, awaitItem())
            active.value = b.copy(id = "never-checked")
            assertEquals(PushRegistrationStatus.NOT_CHECKED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Given every local status when mapped then Settings uses localized resource only`() {
        val expected = listOf(R.string.push_registration_not_checked, R.string.push_registration_checking,
            R.string.push_registration_acknowledged, R.string.push_registration_not_configured,
            R.string.push_registration_contract_rejected, R.string.push_registration_retry,
            R.string.push_registration_sign_in, R.string.push_registration_unregistered)
        assertEquals(expected, PushRegistrationStatus.entries.map(::pushRegistrationStatusResource))
    }
}
