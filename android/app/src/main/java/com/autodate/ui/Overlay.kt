package com.autodate.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.autodate.Config
import com.autodate.screen.Likeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.hypot

/** [target] is the item the heart goes on (index into the profile's likeables), when the card offered a choice. */
sealed class Approval {
    /** [aiComment] is set when [comment] started as an AI rewrite for [target], so edits are measured against that draft. */
    data class Send(val comment: String?, val target: Int? = null, val aiComment: String? = null) : Approval()
    data class LikeOnly(val target: Int? = null) : Approval()
    data object Skip : Approval()
}

/**
 * Windows drawn over Hinge: the status bubble (always) and the approval card (approve mode).
 * All view work is posted to the main thread, so these methods are safe to call from the worker.
 */
class Overlay(private val service: AccessibilityService) {

    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}

    private val config = Config(service)
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = service.getSystemService(WindowManager::class.java)
    private val dp get() = service.resources.displayMetrics.density
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var cancelCard: (() -> Unit)? = null
    private var status = ""
    @Volatile private var seen: Boolean? = null

    fun show() = main.post {
        if (bubble != null) return@post
        val view = TextView(service).apply {
            textSize = 13f
            setTextColor(Palette.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = bubbleBackground(seen)
            elevation = 6 * dp
            maxWidth = (200 * dp).toInt()
            visibility = View.GONE
            setOnClickListener { onTap() }
            setOnLongClickListener { onLongPress(); true }
        }
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val saved = config.bubblePosition
            x = saved?.first ?: (8 * dp).toInt()
            y = saved?.second ?: 0
        }
        draggable(view, params)
        // The window token goes stale if the service is re-bound quickly (app update, toggled off/on).
        if (runCatching { wm.addView(view, params) }.isFailure) return@post
        bubble = view
        bubbleParams = params
        view.text = status
    }

    /** Dragging moves the bubble and remembers where it landed; a tap or long press still works as before. */
    private fun draggable(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var windowX = 0
        var windowY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    windowX = params.x
                    windowY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        view.cancelLongPress()
                        view.isPressed = false
                    }
                    if (dragging) {
                        // x counts from the right edge (Gravity.END), y from the middle of the screen.
                        params.x = windowX - dx.toInt()
                        params.y = windowY + dy.toInt()
                        keepOnScreen(view, params)
                        wm.updateViewLayout(view, params)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) config.bubblePosition = params.x to params.y
                    dragging
                }
                else -> false
            }
        }
        // The pill resizes with its text, and a longer status could push it off the left edge.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val x = params.x
            val y = params.y
            keepOnScreen(v, params)
            if (x != params.x || y != params.y) main.post { if (bubble === v) wm.updateViewLayout(v, params) }
        }
    }

    private fun keepOnScreen(view: View, params: WindowManager.LayoutParams) {
        val screen = wm.currentWindowMetrics.bounds
        val maxY = ((screen.height() - view.height) / 2).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, (screen.width() - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(-maxY, maxY)
    }

    fun remove() = main.post {
        bubble?.let { wm.removeView(it) }
        card?.let { wm.removeView(it) }
        bubble = null
        card = null
    }

    fun setVisible(visible: Boolean) = main.post { bubble?.visibility = if (visible) View.VISIBLE else View.GONE }

    fun setStatus(text: String) {
        status = text
        main.post { bubble?.text = text }
    }

    /** Shows [text] briefly, then goes back to the status. */
    fun flash(text: String, ms: Long = 2500) {
        main.post {
            bubble?.text = text
            // Only the latest flash restores the status; an older one mustn't cut it short.
            main.removeCallbacks(restore)
            main.postDelayed(restore, ms)
        }
    }

    private val restore = Runnable { bubble?.text = status }

    /** Shadow mode's cue: green once the screen has been read, red while it hasn't, null for the normal look. */
    fun setSeen(seen: Boolean?) {
        if (seen == this.seen) return
        this.seen = seen
        main.post { bubble?.background = bubbleBackground(seen) }
    }

    private fun bubbleBackground(seen: Boolean?) = Palette.ripple(
        when (seen) {
            true -> Palette.rounded(Palette.GOOD_SOFT, 22 * dp, Palette.GOOD, (3 * dp).toInt())
            false -> Palette.rounded(Palette.BAD_SOFT, 22 * dp, Palette.BAD, (3 * dp).toInt())
            // Light pill with an accent rim so it still stands out on Hinge's white screens.
            null -> Palette.rounded(Palette.SURFACE, 22 * dp, Palette.ACCENT, (2 * dp).toInt())
        },
    )

    /**
     * Lets touches pass through the bubble, so a gesture aimed at a Hinge button under it reaches
     * Hinge instead of counting as tap-to-stop. Blocks until applied; call from the worker thread.
     */
    fun passThrough(on: Boolean) {
        val done = CountDownLatch(1)
        main.post {
            bubbleParams?.let { p ->
                val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                p.flags = if (on) p.flags or f else p.flags and f.inv()
                bubble?.let { wm.updateViewLayout(it, p) }
            }
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
        if (on) Thread.sleep(60) // the window change reaches input a moment after the call returns
    }

    /** Keeps the screen awake while a session runs (the bubble window carries the flag). */
    fun keepScreenOn(on: Boolean) = main.post {
        val p = bubbleParams ?: return@post
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        bubble?.let { wm.updateViewLayout(it, p) }
    }

    /** Where you last dragged a hidden approval card, in pixels up from the bottom. Kept for the next card. */
    private var hiddenCardY = 0

    /**
     * Blocks the calling (worker) thread until you choose, or returns Skip after [timeoutMs].
     * [comment] is editable via "Edit". [allowLikeOnly] shows the "Like only" button (profiles, not openers).
     * With [targets], the card shows where the heart goes ([target]) and lets you move it. A comment
     * written for another item is greyed out and not sent; [rewrite] (called off the main thread)
     * writes a new one for the item you picked, or throws with the reason it couldn't.
     */
    fun askApproval(
        title: String, body: String, comment: String?, allowLikeOnly: Boolean, sendLabel: String, timeoutMs: Long,
        targets: List<Likeable> = emptyList(), target: Int? = null, rewrite: ((Int) -> String)? = null,
    ): Approval {
        val latch = CountDownLatch(1)
        var result: Approval = Approval.Skip
        main.post {
            val pad = (16 * dp).toInt()
            val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.BOTTOM
                // While editing, the keyboard pushes the card up instead of covering it.
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            lateinit var root: LinearLayout
            var closed = false
            val finish = { a: Approval ->
                closed = true
                result = a
                runCatching { wm.removeView(root) }
                card = null
                cancelCard = null
                latch.countDown()
            }
            val edit = EditText(service).apply {
                setText(comment.orEmpty())
                textSize = 15f
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.TEXT_2)
                hint = "(no comment)"
                isEnabled = false
                background = Palette.rounded(Palette.FIELD, 10 * dp, Palette.BORDER, dp.toInt())
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                maxLines = 6
                visibility = if (comment == null && !allowLikeOnly) View.GONE else View.VISIBLE
            }

            // Where the heart goes. The comment in the box was written for [draftFor]. Moving the heart
            // writes a new one for the new item (unless you've typed your own); until it arrives the old
            // one is greyed out and won't be sent.
            var chosen = target
            var draftFor = target
            var aiDraft: String? = null // the last rewrite, as the AI wrote it
            var userEdited = false
            var settingText = false
            var writingFor: Int? = null // the item a rewrite is in flight for; a late reply for another is dropped
            val drafts = mutableMapOf<Int, String>() // rewrites so far, so moving back doesn't pay twice
            target?.let { t -> comment?.takeIf { it.isNotBlank() }?.let { drafts[t] = it } }
            val nameOf = { i: Int? -> targets.firstOrNull { it.index == i }?.let(::targetName) ?: "nothing" }
            val stale = { chosen != draftFor && !userEdited && edit.text.isNotBlank() }
            val heart = button("") {}
            val picker = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val staleNote = TextView(service).apply {
                textSize = 13f
                setTextColor(Palette.TEXT_2)
                setPadding(0, (6 * dp).toInt(), 0, 0)
                visibility = View.GONE
            }
            val rewriteButton = button("Rewrite for this one") {}
            lateinit var sendButton: Button
            val refresh = {
                heart.text = "♥ on ${nameOf(chosen)}  ·  Change"
                val isStale = stale()
                edit.alpha = if (isStale) 0.45f else 1f
                staleNote.visibility = if (isStale) View.VISIBLE else View.GONE
                if (isStale) staleNote.text = "Written for ${nameOf(draftFor)}. Rewrite it or edit it, or the like goes without a comment."
                rewriteButton.visibility = if (rewrite != null && chosen != draftFor) View.VISIBLE else View.GONE
                rewriteButton.isEnabled = writingFor == null
                rewriteButton.text = if (writingFor != null) "Writing…" else "Rewrite for this one"
                sendButton.text = if (isStale) "$sendLabel without comment" else sendLabel
            }
            edit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (settingText || !edit.isEnabled) return
                    userEdited = true
                    refresh()
                }
            })
            heart.setOnClickListener { picker.visibility = if (picker.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            val showDraft = { i: Int, text: String ->
                settingText = true
                edit.setText(text)
                settingText = false
                edit.visibility = View.VISIBLE
                draftFor = i
                userEdited = false
            }
            val startRewrite = startRewrite@{ i: Int ->
                val fn = rewrite ?: return@startRewrite
                writingFor = i
                refresh()
                thread {
                    val r = runCatching { fn(i) }
                    main.post {
                        if (closed || writingFor != i) return@post
                        writingFor = null
                        r.onSuccess { text ->
                            drafts[i] = text
                            if (chosen == i) {
                                showDraft(i, text)
                                aiDraft = text
                            }
                            refresh()
                        }.onFailure { e ->
                            refresh()
                            staleNote.visibility = View.VISIBLE
                            staleNote.text = "Couldn't rewrite: ${e.message}"
                        }
                    }
                }
            }
            val pick = { i: Int ->
                chosen = i
                picker.visibility = View.GONE
                // Your own typing stays put; otherwise the comment follows the heart.
                if (i != draftFor && !userEdited) {
                    val cached = drafts[i]
                    if (cached != null) {
                        writingFor = null
                        showDraft(i, cached)
                        aiDraft = cached.takeIf { i != target }
                    } else startRewrite(i)
                }
                refresh()
            }
            // Photos as one row of numbers, everything else (prompts, voice, polls) one per line.
            val photos = targets.filter { it.kind == "photo" }
            if (photos.isNotEmpty()) picker.addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * dp).toInt(), 0, 0)
                addView(TextView(service).apply { text = "Photo"; textSize = 14f; setTextColor(Palette.TEXT_2); setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) })
                photos.forEachIndexed { n, l -> addView(button("${n + 1}") { pick(l.index) }) }
            })
            targets.filter { it.kind != "photo" }.forEach { l ->
                picker.addView(button(targetName(l)) { pick(l.index) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), 0) }
                    maxLines = 2
                })
            }
            rewriteButton.setOnClickListener { chosen?.let { startRewrite(it) } }
            // "Hide" shrinks the card to its title so you can scroll the profile behind it; tap the title to bring it back.
            val titleView = TextView(service)
            val details = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            val setHidden = { hidden: Boolean ->
                details.visibility = if (hidden) View.GONE else View.VISIBLE
                titleView.text = if (hidden) "$title  ·  tap to show, drag to move" else title
                // Hidden, it sits where you last dragged it (off the keyboard, say); shown, it's back at the bottom.
                params.y = if (hidden) hiddenCardY else 0
                runCatching { wm.updateViewLayout(root, params) }
                // Give the screen back to Hinge, even if "Edit" had taken the keyboard.
                if (hidden && params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
                    service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    wm.updateViewLayout(root, params)
                }
            }
            // Tap toggles; while hidden, dragging moves the bar up or down.
            val slop = ViewConfiguration.get(service).scaledTouchSlop
            var downRawY = 0f
            var startY = 0
            var dragging = false
            titleView.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downRawY = e.rawY; startY = params.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> if (details.visibility == View.GONE) {
                        val dy = (downRawY - e.rawY).toInt() // gravity is BOTTOM: y grows upward
                        if (dragging || kotlin.math.abs(dy) > slop) {
                            dragging = true
                            val maxY = service.resources.displayMetrics.heightPixels - root.height
                            params.y = (startY + dy).coerceIn(0, maxOf(0, maxY))
                            hiddenCardY = params.y
                            runCatching { wm.updateViewLayout(root, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragging) setHidden(details.visibility == View.VISIBLE)
                }
                true
            }
            val sheet = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = Palette.rounded(Palette.SURFACE, 20 * dp, Palette.BORDER, dp.toInt())
                elevation = 8 * dp
                addView(titleView.apply { text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Palette.TEXT) })
                addView(details)
            }
            details.apply {
                addView(TextView(service).apply {
                    text = body
                    textSize = 14f
                    setTextColor(Palette.TEXT_2)
                    setLineSpacing(2 * dp, 1f)
                    setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt())
                })
                if (targets.isNotEmpty()) {
                    addView(heart.apply {
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), (8 * dp).toInt()) }
                    })
                    addView(picker)
                }
                addView(edit)
                addView(staleNote)
                if (targets.isNotEmpty()) addView(rewriteButton.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0) }
                })
                sendButton = button(sendLabel, primary = true) {
                    val text = edit.text.toString().trim().ifEmpty { null }.takeUnless { stale() }
                    finish(Approval.Send(text, chosen, aiDraft?.takeIf { text != null && draftFor == chosen }))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt(), 0) }
                }
                addView(sendButton)
                if (targets.isNotEmpty()) refresh() else rewriteButton.visibility = View.GONE
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                    if (allowLikeOnly) addView(button("Like only") { finish(Approval.LikeOnly(chosen)) })
                    addView(button("Edit") {
                        // Make the card focusable so the keyboard can open. NOT_TOUCH_MODAL keeps touches
                        // outside the card reaching Hinge and the bubble; a focusable window swallows them otherwise.
                        params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        wm.updateViewLayout(root, params)
                        edit.visibility = View.VISIBLE
                        edit.isEnabled = true
                        edit.requestFocus()
                        edit.setSelection(edit.text.length)
                        service.getSystemService(InputMethodManager::class.java).showSoftInput(edit, 0)
                    })
                    addView(button("Hide") { setHidden(true) })
                    addView(button("Skip") { finish(Approval.Skip) })
                })
            }
            // Margin around the sheet leaves room for its shadow and keeps it above the gesture bar.
            // (Overlay windows get no insets callbacks, so read the nav bar height once.)
            root = LinearLayout(service).apply {
                val m = (12 * dp).toInt()
                val nav = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
                setPadding(m, 2 * m, m, m + nav)
                addView(sheet, LinearLayout.LayoutParams(-1, -2))
            }
            if (runCatching { wm.addView(root, params) }.isFailure) return@post latch.countDown()
            card = root
            cancelCard = { finish(Approval.Skip) }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            main.post { card?.let { runCatching { wm.removeView(it) } }; card = null; cancelCard = null }
        }
        return result
    }

    /** "Photo 3", or a prompt's question ("Prompt: My simple pleasures"), short enough for a button. */
    private fun targetName(l: Likeable): String = when (l.kind) {
        "photo" -> l.label.replaceFirstChar { it.uppercase() }
        "prompt" -> "Prompt: " + l.label.removePrefix("Prompt: ").substringBefore(". Answer:").substringBefore(" — ").take(48)
        "poll" -> "Poll: " + l.label.substringAfter(" — ").removePrefix("Option: ").take(48)
        else -> "${l.kind.replaceFirstChar { it.uppercase() }}: ${l.label.take(40)}"
    }

    /** Closes an open approval card. Call after stopping the session, so the screen is yours right away. */
    fun cancelApproval() = main.post { cancelCard?.invoke() }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(service).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = (48 * dp).toInt()
        minimumHeight = (48 * dp).toInt()
        if (primary) {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.SURFACE)
            background = Palette.ripple(Palette.rounded(Palette.ACCENT, 12 * dp))
        } else {
            setTextColor(Palette.TEXT)
            background = Palette.ripple(Palette.rounded(Palette.SURFACE, 12 * dp, Palette.BORDER, dp.toInt()))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (4 * dp).toInt()
            marginEnd = (4 * dp).toInt()
        }
    }

    private fun overlayParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )
}

/** Posts a notification on the app's channel. */
object Notifier {
    private const val CHANNEL = "autodate"

    fun post(context: Context, title: String, text: String) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Proxy Swiper", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}
