package io.woowtech.odoo.ui

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回歸防線 D-R2-1（2026-09-29 Android 第二輪 A19-02／A19-05／A19-06，commands.log 01:03:23–01:03:50）：
 * App 已登入且在前景時收到外部 VIEW 深層連結（`apporoodoo-dev://open`，LaunchState WARM），
 * task #66 從 sz=1 變 sz=2——同一個 task 疊出第二個 MainActivity（ActivityRecord 28108721／258080556），
 * 按返回回到較舊的那個實例（第二個 WebView、第二組 ProcessLifecycle 觀察者），App 沒有離開。
 *
 * 根因：manifest 的 MainActivity 沒有 launchMode（= standard），每個進來的 VIEW intent 都會新建實例。
 * 推播點擊用 NEW_TASK|CLEAR_TOP，外部 scheme 連結沒有，所以只有外部連結會疊出來。
 *
 * 修正：MainActivity 是整個 App 唯一的 Activity（單一 WebView），改成 singleTask——既有實例一律收
 * onNewIntent（handleDeepLinkIntent／TestHooks 本來就在 onNewIntent 處理），不會再疊出第二個。
 *
 * Robolectric 讀的是這個 variant 合併後的 manifest（isIncludeAndroidResources = true），兩個品牌共用
 * `app/src/main/AndroidManifest.xml`，所以 woowtech／apporo 兩個 unit test task 都會驗到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityExternalLinkInstanceTest {

    @Test
    fun `Given an external VIEW link while the app runs then the single MainActivity instance receives it`() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)

        assertEquals(
            "MainActivity must be singleTask so an external link reuses the running instance",
            ActivityInfo.LAUNCH_SINGLE_TASK,
            info.launchMode,
        )
    }
}
