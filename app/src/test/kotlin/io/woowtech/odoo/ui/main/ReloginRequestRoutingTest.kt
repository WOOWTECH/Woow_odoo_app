package io.woowtech.odoo.ui.main

import io.woowtech.odoo.data.repository.ReloginReason
import io.woowtech.odoo.data.repository.ReloginRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * LIVE-0927 Android r2：`MainScreen` 收到 re-login 要求時一律開選單頁（MainScreen.kt:108-113），
 * 是「困在 Configuration」的另一個入口。目前帳號要重新登入 → 預填的登入頁；別的帳號（背景推播
 * re-auth 失敗）→ 仍開帳號選單讓使用者自己處理，不能把它套到目前帳號的登入頁上。
 */
class ReloginRequestRoutingTest {

    @Test
    fun `Given the active account needs re-login when routing the request then the prefilled sign-in opens`() {
        val request = ReloginRequest(accountId = "active", reason = ReloginReason.INVALID_CREDENTIALS)

        assertEquals(ReloginRoute.SignIn, reloginRouteFor(request, activeAccountId = "active"))
    }

    @Test
    fun `Given another account needs re-login when routing the request then the account menu opens`() {
        val request = ReloginRequest(accountId = "other", reason = ReloginReason.REAUTH_CIRCUIT_OPEN)

        assertEquals(ReloginRoute.AccountMenu, reloginRouteFor(request, activeAccountId = "active"))
    }
}
