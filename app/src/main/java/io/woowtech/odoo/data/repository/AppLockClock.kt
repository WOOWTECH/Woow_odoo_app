package io.woowtech.odoo.data.repository

import android.os.SystemClock
import java.io.File
import javax.inject.Inject

/** A coherent time sample used to evaluate a persisted App Lock lockout. */
data class AppLockTime(
    val elapsedRealtimeMs: Long,
    val wallClockMs: Long,
    val bootId: String,
)

/**
 * Injectable clock for App Lock.
 *
 * [SystemClock.elapsedRealtime] is authoritative while Android remains on the same boot because it
 * cannot be changed from Date & time settings. The kernel boot ID lets the repository detect when
 * that monotonic epoch has been reset. `/proc/stat` and a coarse boot-epoch estimate are defensive
 * fallbacks for devices that hide `/proc/sys/kernel/random/boot_id`.
 */
open class AppLockClock @Inject constructor() {
    private val currentBootId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readFirstNonBlankLine(BOOT_ID_PATH)
            ?.let { "kernel:$it" }
            ?: readBootTimeFromProcStat()
            ?: estimatedBootEpochId()
    }

    open fun now(): AppLockTime = AppLockTime(
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        wallClockMs = System.currentTimeMillis(),
        bootId = currentBootId,
    )

    private fun readFirstNonBlankLine(path: String): String? = runCatching {
        File(path).useLines { lines -> lines.firstOrNull { it.isNotBlank() }?.trim() }
    }.getOrNull()

    private fun readBootTimeFromProcStat(): String? = runCatching {
        File(PROC_STAT_PATH).useLines { lines ->
            lines.firstOrNull { it.startsWith("btime ") }
                ?.substringAfter(' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "btime:$it" }
        }
    }.getOrNull()

    private fun estimatedBootEpochId(): String {
        val bootEpochMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return "estimated:${Math.floorDiv(bootEpochMs, BOOT_EPOCH_BUCKET_MS)}"
    }

    private companion object {
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        const val PROC_STAT_PATH = "/proc/stat"
        const val BOOT_EPOCH_BUCKET_MS = 5 * 60 * 1_000L
    }
}
