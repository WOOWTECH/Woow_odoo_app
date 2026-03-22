package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CacheRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var cacheDir: File
    private lateinit var context: android.content.Context

    @BeforeEach
    fun setup() {
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir
    }

    @Test
    fun `Given cache dir with files when clearAppCache then dir emptied`() = runTest {
        File(cacheDir, "test1.tmp").writeText("data1")
        File(cacheDir, "test2.tmp").writeText("data2")
        assertTrue(cacheDir.listFiles()!!.isNotEmpty())

        val repo = CacheRepository(context)
        repo.clearAppCache()

        val remaining = cacheDir.listFiles()
        assertTrue(remaining == null || remaining.isEmpty())
    }

    @Test
    fun `Given empty cache when calculateCacheSize then returns 0`() = runTest {
        val repo = CacheRepository(context)
        val size = repo.calculateCacheSize()
        assertEquals(0L, size)
    }

    @Test
    fun `Given cache with known files when calculateCacheSize then returns correct total`() = runTest {
        File(cacheDir, "file1.bin").writeBytes(ByteArray(1024))
        File(cacheDir, "file2.bin").writeBytes(ByteArray(2048))

        val repo = CacheRepository(context)
        val size = repo.calculateCacheSize()
        assertEquals(3072L, size)
    }
}
