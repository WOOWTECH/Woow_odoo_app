package io.woowtech.odoo.ui.main

import io.woowtech.odoo.ui.main.FileChooserPlan.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W2-4 L7 (Pixel 7a, Play vc5): tapping "Camera" in the file chooser did nothing — Android refuses
 * `ACTION_IMAGE_CAPTURE` from an app that declares CAMERA without holding it. The camera is now offered
 * only with the permission granted, the permission is asked for first, and the WebView callback is
 * answered on every path so the page can open the chooser again.
 */
class FileChooserPlanTest {

    @Test
    fun `Given camera granted when the page wants an image then the chooser offers the camera`() {
        assertEquals(Step.CHOOSER_WITH_CAMERA, FileChooserPlan.firstStep(wantsCamera = true, cameraGranted = true))
    }

    @Test
    fun `Given camera not granted when the page wants an image then the permission is requested first`() {
        assertEquals(Step.REQUEST_CAMERA_PERMISSION, FileChooserPlan.firstStep(wantsCamera = true, cameraGranted = false))
    }

    @Test
    fun `Given a non image field when the chooser opens then files only and no permission prompt`() {
        assertEquals(Step.CHOOSER_FILES_ONLY, FileChooserPlan.firstStep(wantsCamera = false, cameraGranted = false))
        assertEquals(Step.CHOOSER_FILES_ONLY, FileChooserPlan.firstStep(wantsCamera = false, cameraGranted = true))
    }

    @Test
    fun `Given the permission answer when granted then camera else files only`() {
        assertEquals(Step.CHOOSER_WITH_CAMERA, FileChooserPlan.afterCameraPermission(granted = true))
        // Also the permanently denied case: the system answers "denied" at once, without a dialog.
        assertEquals(Step.CHOOSER_FILES_ONLY, FileChooserPlan.afterCameraPermission(granted = false))
    }

    @Test
    fun `Given accept lists when checked then only image capable lists want the camera`() {
        assertTrue(FileChooserPlan.acceptsCameraPhoto(null))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(emptyArray()))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(arrayOf("")))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(arrayOf("*/*")))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(arrayOf("image/*")))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(arrayOf("application/pdf", "image/png")))
        assertTrue(FileChooserPlan.acceptsCameraPhoto(arrayOf(".pdf,.JPG")))
        assertFalse(FileChooserPlan.acceptsCameraPhoto(arrayOf("application/pdf")))
        assertFalse(FileChooserPlan.acceptsCameraPhoto(arrayOf(".pdf", "")))
    }

    @Test
    fun `Given accept lists when the files intent type is chosen then a MIME type or everything`() {
        assertEquals("*/*", FileChooserPlan.contentMimeType(null))
        assertEquals("*/*", FileChooserPlan.contentMimeType(arrayOf("")))
        assertEquals("*/*", FileChooserPlan.contentMimeType(arrayOf(".pdf")))
        assertEquals("image/*", FileChooserPlan.contentMimeType(arrayOf("image/*", "application/pdf")))
        assertEquals("application/pdf", FileChooserPlan.contentMimeType(arrayOf("application/pdf, image/png")))
    }

    @Test
    fun `Given a pending request when a result arrives then the callback is answered exactly once`() {
        val holder = FileChooserCallbackHolder<String>()
        val answers = mutableListOf<String?>()
        holder.begin { answers += it }
        assertTrue(holder.isPending)
        holder.deliver("content://picked")
        holder.deliver("content://late")
        holder.cancel()
        assertEquals(listOf<String?>("content://picked"), answers)
        assertFalse(holder.isPending)
    }

    @Test
    fun `Given a pending request when cancelled or denied then the callback gets null`() {
        val holder = FileChooserCallbackHolder<String>()
        val answers = mutableListOf<String?>()
        holder.begin { answers += it }
        holder.cancel()
        assertEquals(listOf<String?>(null), answers)
        assertFalse(holder.isPending)
    }

    @Test
    fun `Given an unanswered request when a new one begins then the old one gets null first`() {
        val holder = FileChooserCallbackHolder<String>()
        val first = mutableListOf<String?>()
        val second = mutableListOf<String?>()
        holder.begin { first += it }
        holder.begin { second += it }
        assertEquals(listOf<String?>(null), first)
        holder.deliver("content://second")
        assertEquals(listOf<String?>("content://second"), second)
        assertEquals(listOf<String?>(null), first)
    }

    @Test
    fun `Given no pending request when answered then nothing happens`() {
        val holder = FileChooserCallbackHolder<String>()
        holder.deliver("x")
        holder.cancel()
        assertFalse(holder.isPending)
    }
}
