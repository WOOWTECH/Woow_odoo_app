package io.woowtech.odoo.ui.main

/**
 * Decisions behind the WebView file chooser (`<input type="file">`), kept free of Android types so
 * they can be verified on the JVM.
 *
 * Background (W2-4 L7, 2026-10-08): the chooser always offered "Camera" through
 * `EXTRA_INITIAL_INTENTS`, but the manifest declares `CAMERA` and the app never requested it at
 * runtime. Android then refuses `ACTION_IMAGE_CAPTURE` from an app that declares but has not been
 * granted `CAMERA` ("Permission Denial … with revoked permission android.permission.CAMERA"), so tapping
 * "Camera" did nothing. Now the camera option is only offered once the permission is granted; when it
 * is not, the permission is requested first and the chooser opens afterwards — with the camera when
 * granted, files only when denied (a permanently denied permission answers "denied" at once, without a
 * dialog, so the user goes straight to the files).
 */
internal object FileChooserPlan {

    /** What to do when the page asks for a file. */
    enum class Step {
        /** Open the chooser with the camera as an extra option. */
        CHOOSER_WITH_CAMERA,

        /** Open the chooser with files only. */
        CHOOSER_FILES_ONLY,

        /** Ask for the CAMERA permission first; continue with [afterCameraPermission]. */
        REQUEST_CAMERA_PERMISSION,
    }

    /**
     * Whether a photo taken with the camera can satisfy the page's `accept` list. No list, a blank
     * entry, a wildcard, any `image` type or a common image extension accept a JPEG. A list that only
     * names other types (a PDF field, say) gets no camera, and therefore no permission prompt.
     */
    fun acceptsCameraPhoto(acceptTypes: Array<String>?): Boolean {
        val types = acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }
        if (types.none { it.isNotEmpty() }) return true
        return types.any { type ->
            type == "*/*" || type == "*" || type.startsWith("image/") || type in IMAGE_EXTENSIONS
        }
    }

    /** First step for a chooser request. */
    fun firstStep(wantsCamera: Boolean, cameraGranted: Boolean): Step = when {
        !wantsCamera -> Step.CHOOSER_FILES_ONLY
        cameraGranted -> Step.CHOOSER_WITH_CAMERA
        else -> Step.REQUEST_CAMERA_PERMISSION
    }

    /** The chooser to open once the CAMERA permission request answered. */
    fun afterCameraPermission(granted: Boolean): Step =
        if (granted) Step.CHOOSER_WITH_CAMERA else Step.CHOOSER_FILES_ONLY

    /** MIME type for the files intent: the page's first accepted type, or everything. */
    fun contentMimeType(acceptTypes: Array<String>?): String {
        val first = acceptTypes?.firstOrNull()?.split(',')?.firstOrNull()?.trim()
        return if (first.isNullOrBlank() || first.startsWith(".")) "*/*" else first
    }

    private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp", ".gif")
}

/**
 * Holds the WebView's pending file-chooser callback and guarantees it is answered exactly once.
 *
 * The WebView will not open another chooser until the previous callback was answered, so every path —
 * a result, a cancel, a denied permission, an exception, a request from a WebView that no longer belongs
 * to the displayed account, the screen leaving — must end in [deliver] or [cancel]. Main thread only.
 */
internal class FileChooserCallbackHolder<T> {
    private var pending: ((T?) -> Unit)? = null

    /** Whether a request is waiting for its answer. */
    val isPending: Boolean get() = pending != null

    /** Starts a request; an older unanswered one is answered with null first. */
    fun begin(callback: (T?) -> Unit) {
        cancel()
        pending = callback
    }

    /** Answers the pending request with [value] (null = nothing chosen); no-op when none is pending. */
    fun deliver(value: T?) {
        val callback = pending ?: return
        pending = null
        callback(value)
    }

    /** Answers the pending request with null. */
    fun cancel() = deliver(null)
}
