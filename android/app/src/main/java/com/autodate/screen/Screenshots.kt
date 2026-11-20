package com.autodate.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import com.autodate.agent.Control
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Wraps AccessibilityService.takeScreenshot, which Android rate-limits to roughly one per second. */
class Screenshots(private val service: AccessibilityService) {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastAt = 0L

    /** Async. [onResult] runs on a background thread with a software bitmap, or null and a reason. */
    fun take(onResult: (Bitmap?, String?) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < MIN_INTERVAL_MS) {
            onResult(null, "too soon after previous")
            return
        }
        lastAt = now
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                onResult(bitmap, if (bitmap == null) "could not decode" else null)
            }

            override fun onFailure(errorCode: Int) = onResult(null, describe(errorCode))
        })
    }

    /** Blocking, worker thread only: waits out the rate limit and returns a downscaled JPEG. */
    fun jpeg(control: Control, maxWidth: Int = 540, quality: Int = 70): ByteArray? {
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastAt)
        if (wait > 0) control.sleep(wait + 20)
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        take { bmp, _ ->
            if (bmp != null) {
                val scaled = if (bmp.width > maxWidth) {
                    Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true)
                } else {
                    bmp
                }
                out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    fun shutdown() = executor.shutdown()

    companion object {
        private const val MIN_INTERVAL_MS = 1100L

        fun describe(code: Int) = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval too short"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid window"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window (Hinge blocks screenshots here)"
            else -> "code $code"
        }
    }
}
