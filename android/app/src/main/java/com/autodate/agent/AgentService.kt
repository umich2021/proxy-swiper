package com.autodate.agent

import android.util.Base64
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.autodate.Config
import com.autodate.Mode
import com.autodate.brain.BrainClient
import com.autodate.data.CaptureStore
import com.autodate.data.Db
import com.autodate.screen.HINGE
import com.autodate.screen.ScreenReader
import com.autodate.screen.Screenshots
import com.autodate.screen.Selectors
import com.autodate.ui.Overlay
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * The one accessibility service. Scoped to Hinge in res/xml/agent_service.xml. Routes Hinge events
 * to the current mode, owns the bubble, and runs approve/auto sessions on a worker thread.
 */
class AgentService : AccessibilityService() {

    lateinit var config: Config
    lateinit var db: Db
    lateinit var selectors: Selectors
    lateinit var reader: ScreenReader
    lateinit var shots: Screenshots
    lateinit var gestures: GestureEngine
    lateinit var driver: Driver
    lateinit var brain: BrainClient
    lateinit var overlay: Overlay
    lateinit var inspector: Inspector
    lateinit var shadow: ShadowObserver
    lateinit var inbox: InboxRunner
    val control = Control()

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    val sessionRunning get() = worker?.isAlive == true

    // Show the bubble only while Hinge is in front (or while we're busy).
    private val bubblePoll = object : Runnable {
        override fun run() {
            overlay.setVisible(reader.root() != null || sessionRunning)
            main.postDelayed(this, 1000)
        }
    }

    /** Types into whatever field has the keyboard, one key at a time. Needs flagInputMethodEditor (Android 13+). */
    fun commitText(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val parts = Jitter.graphemes(text)
        for ((i, g) in parts.withIndex()) {
            val ic = inputMethod?.currentInputConnection ?: return false
            ic.commitText(g, 1, null) // returns nothing here; addComment reads the box back to check
            if (i < parts.lastIndex) control.sleep(Jitter.keyPause(g))
        }
        return true
    }

    /** The text in the field that has the keyboard, or null if it can't be read. */
    fun inputFieldText(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return inputMethod?.currentInputConnection?.getSurroundingText(1000, 1000, 0)?.text?.toString()
    }

    /** True while a keyboard is on screen. */
    fun keyboardOpen(): Boolean = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /**
     * Closes the keyboard with Back, only if one is open, and waits for it to go. Back with no
     * keyboard up would act on Hinge itself (close a sheet, or leave Hinge from Discover).
     * Returns true if a keyboard was open.
     */
    fun hideKeyboard(): Boolean {
        if (!keyboardOpen()) return false
        performGlobalAction(GLOBAL_ACTION_BACK)
        val end = System.currentTimeMillis() + 1500
        while (keyboardOpen() && System.currentTimeMillis() < end) control.sleep(150)
        control.sleep(Jitter.wait(300, 600))
        return true
    }

    /** Brings Hinge back to the front. */
    fun openHinge(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(HINGE) ?: return false
        return runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    override fun onServiceConnected() {
        config = Config(this)
        if (config.shadowCommentsSince == 0L) config.shadowCommentsSince = System.currentTimeMillis()
        db = Db(this)
        selectors = Selectors(this)
        reader = ScreenReader(this, selectors)
        shots = Screenshots(this)
        gestures = GestureEngine(this, control) {
            control.check()
            if (reader.root() == null) throw Stopped("Hinge isn't in front")
        }
        driver = Driver(this, selectors, reader, gestures, control)
        brain = BrainClient(config)
        overlay = Overlay(this)
        inspector = Inspector(this)
        gestures.onGesture = { overlay.passThrough(it) }
        shadow = ShadowObserver(this)
        inbox = InboxRunner(this)
        overlay.onTap = ::onBubbleTap
        overlay.onLongPress = ::onBubbleLongPress
        overlay.show()
        idleStatus()
        main.post(bubblePoll)
        thread { CaptureStore.pruneOldProfiles(this) }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != HINGE) return
        if (config.mode != Mode.SHADOW) overlay.setSeen(null) // the mode may have just changed
        if (sessionRunning) return // these are our own taps
        when (config.mode) {
            Mode.INSPECT -> inspector.onEvent(event)
            Mode.SHADOW -> shadow.onEvent(event)
            else -> {}
        }
    }

    /** Volume-down is the hardware stop button while a session runs. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (sessionRunning && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                control.stop("stopped with volume-down")
                overlay.cancelApproval()
            }
            return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        control.stop("accessibility service turned off")
        main.removeCallbacksAndMessages(null)
        overlay.remove()
        shadow.cancel()
        inspector.shutdown()
        shots.shutdown()
        instance = null
        super.onDestroy()
    }

    /** Starts an approve/auto session. Returns an error message, or null if it started. Open Hinge after. */
    fun startSession(): String? {
        if (sessionRunning) return "A session is already running"
        if (config.mode != Mode.APPROVE && config.mode != Mode.AUTO) return "Switch to Co-pilot or Autopilot to start a session"
        if (!config.brainConfigured) return "Set the proxy URL and device token first"
        likeCapMessage()?.let { return it }
        control.reset()
        overlay.keepScreenOn(true)
        worker = thread(name = "autodate-session") {
            try {
                SessionController(this).run()
            } finally {
                overlay.keepScreenOn(false)
                main.post { idleStatus() }
            }
        }
        return null
    }

    fun stopSession() = control.stop("stopped from the app")

    /**
     * Few-shot examples, refreshed at most daily (or right after you rate profiles) so the cached prompt
     * prefix stays stable. Rated examples get their first saved photo attached, so the AI can learn
     * which faces you find attractive. Photos are kept in memory, not in prefs.
     */
    fun examples(): JSONArray {
        val stale = System.currentTimeMillis() - config.examplesAt > 24 * 3600_000L
        val reviews = db.reviewsVersion()
        if (stale || reviews != config.examplesReviews || (config.examplesJson == "[]" && db.labelCount() > 0)) {
            config.examplesJson = db.examples().toString()
            config.examplesAt = System.currentTimeMillis()
            config.examplesReviews = reviews
        }
        synchronized(this) {
            if (examplesCache?.first != config.examplesJson) examplesCache = config.examplesJson to withPhotos(JSONArray(config.examplesJson))
            return JSONArray(examplesCache!!.second.toString())
        }
    }

    private var examplesCache: Pair<String, JSONArray>? = null

    private fun withPhotos(examples: JSONArray): JSONArray {
        for (i in 0 until examples.length()) {
            val e = examples.getJSONObject(i)
            val key = e.optString("profile_key").ifEmpty { null } ?: continue
            e.remove("profile_key")
            val photo = CaptureStore.profileImages(this, key).firstOrNull() ?: continue
            runCatching { Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP) }.getOrNull()?.let { e.put("image", it) }
        }
        return examples
    }

    /** Non-null when today's likes are used up, so a session would stop right away. */
    fun likeCapMessage(): String? {
        val sent = db.likesToday()
        val cap = config.dailyLikeCap
        return if (sent >= cap) "$sent/$cap likes sent today. Resets at midnight, or raise the cap in Settings" else null
    }

    fun idleStatus() = overlay.setStatus(
        when (config.mode) {
            Mode.INSPECT -> if (config.autoCapture) "REC · auto" else "REC"
            Mode.SHADOW -> "Learn · tap to ask AI"
            Mode.APPROVE, Mode.AUTO ->
                if (::db.isInitialized && db.likesToday() >= config.dailyLikeCap) "${config.mode.name.lowercase()} · out of likes today"
                else "${config.mode.name.lowercase()} · tap to start"
        },
    )

    private fun onBubbleTap() {
        when {
            sessionRunning -> {
                control.stop("stopped from the bubble")
                overlay.cancelApproval()
            }
            config.mode == Mode.INSPECT -> inspector.captureManual()
            config.mode == Mode.SHADOW -> if (config.brainConfigured) shadow.askNow() else overlay.flash("Set the proxy in the app first")
            // Approve/auto while idle: tap the bubble to start (or resume after a stop).
            else -> startSession()?.let { overlay.flash(it) }
        }
    }

    private fun onBubbleLongPress() {
        when {
            config.mode == Mode.INSPECT && !sessionRunning -> {
                config.autoCapture = !config.autoCapture
                idleStatus()
            }
        }
    }

    companion object {
        /** autoDate's own screen is in front (set by MainActivity). */
        @Volatile
        var appInFront = false

        @Volatile
        var instance: AgentService? = null
            private set
    }
}
