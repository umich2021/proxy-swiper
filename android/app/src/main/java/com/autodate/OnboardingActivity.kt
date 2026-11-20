package com.autodate

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.autodate.screen.HINGE
import com.autodate.ui.CelebrateView
import com.autodate.ui.Kit.badge
import com.autodate.ui.Kit.card
import com.autodate.ui.Kit.dp
import com.autodate.ui.Kit.field
import com.autodate.ui.Kit.label
import com.autodate.ui.Kit.lp
import com.autodate.ui.Kit.mt
import com.autodate.ui.Kit.primary
import com.autodate.ui.Kit.quiet
import com.autodate.ui.Kit.row
import com.autodate.ui.Kit.secondary
import com.autodate.ui.Kit.stepLine
import com.autodate.ui.Kit.title
import com.autodate.ui.Palette
import com.autodate.ui.SettingsDemoView
import com.autodate.ui.StepIllustration
import com.autodate.ui.SwipeDemoView
import kotlin.concurrent.thread

/**
 * First-run walkthrough: what autoDate is, how it works, the risks, then each setup step with an
 * animation of the exact taps. Shown until it's finished once; Settings → App can replay it.
 */
class OnboardingActivity : Activity() {

    /** One screen. [primary] is the big button's label and action; [secondary] is the quiet one under it. */
    private class Page(
        val key: String,
        val view: View,
        val primary: () -> Pair<String, () -> Unit>,
        val secondary: (() -> Pair<String, () -> Unit>?)? = null,
        val counted: Boolean = true,
        val onShow: () -> Unit = {},
    )

    private lateinit var config: Config
    private lateinit var pages: List<Page>
    private var index = 0
    /** Opened from Home to fix one step: finishing that step goes back instead of on through the guide. */
    private var jumpedIn = false
    private lateinit var holder: FrameLayout
    private lateinit var primaryButton: android.widget.Button
    private lateinit var secondaryButton: android.widget.Button
    private lateinit var backButton: TextView
    private lateinit var progress: LinearLayout
    private lateinit var stepLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    // live bits
    private lateinit var accessStatus: TextView
    private lateinit var restrictedHelp: View
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var brainStatus: TextView
    private var connecting = false
    private val modeCards = mutableMapOf<Mode, LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        pages = buildPages()
        val asked = intent.getStringExtra(EXTRA_PAGE)?.let { k -> pages.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
        index = savedInstanceState?.getInt("page", 0)?.coerceIn(0, pages.lastIndex) ?: asked ?: 0
        jumpedIn = asked != null

        holder = FrameLayout(this)
        primaryButton = primary("") {}
        secondaryButton = quiet("") {}
        backButton = label("‹  Back", 16f, Palette.TEXT_2, bold = true).apply {
            setPadding(dp(4), dp(10), dp(16), dp(10))
            background = Palette.ripple(Palette.rounded(0, dp(20).toFloat()))
            setOnClickListener { back() }
        }
        stepLabel = label("", 13f, Palette.TEXT_2, bold = true)
        progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(row(backButton, View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }, stepLabel))
            addView(progress, lp(6, height = dp(4)))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            addView(primaryButton, lp())
            addView(secondaryButton, lp(4))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(topBar, lp())
            addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, lp())
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
                WindowInsets.CONSUMED
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        show(index, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", index)
    }

    @Deprecated("Android 12 and older")
    override fun onBackPressed() = back()

    override fun onResume() {
        super.onResume()
        // Coming back from Android settings: re-check whatever this page is waiting on.
        if (::pages.isInitialized) {
            pages[index].onShow()
            refreshButtons()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun back() {
        if (jumpedIn) finish()
        else if (index > 0) show(index - 1, -1) else finish()
    }

    private fun next() {
        if (jumpedIn) finish()
        else if (index < pages.lastIndex) show(index + 1, 1) else finishOnboarding()
    }

    /** Swaps to page [i], sliding in from the side [dir] points to (0 = no animation). */
    private fun show(i: Int, dir: Int) {
        stopPolling()
        val old = holder.getChildAt(0)
        index = i
        val page = pages[i]
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            (page.view.parent as? ViewGroup)?.removeView(page.view)
            addView(page.view, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        holder.addView(scroll)
        if (old != null) {
            if (dir == 0) holder.removeView(old)
            else {
                val shift = holder.width * 0.18f * dir
                old.animate().translationX(-shift).alpha(0f).setDuration(200).withEndAction { holder.removeView(old) }.start()
                scroll.translationX = shift
                scroll.alpha = 0f
                scroll.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
        page.onShow()
        drawProgress()
        refreshButtons()
    }

    private fun drawProgress() {
        val counted = pages.filter { it.counted }
        val at = counted.indexOf(pages[index])
        val visible = at >= 0
        progress.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        stepLabel.text = if (visible) "Step ${at + 1} of ${counted.size}" else ""
        backButton.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        progress.removeAllViews()
        counted.forEachIndexed { j, _ ->
            progress.addView(View(this).apply {
                background = Palette.rounded(if (j <= at) Palette.ACCENT else Palette.BORDER, dp(2).toFloat())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (j > 0) marginStart = dp(4) })
        }
    }

    private fun refreshButtons() {
        val page = pages[index]
        val (label, action) = page.primary()
        primaryButton.text = label
        primaryButton.setOnClickListener { action() }
        val sec = page.secondary?.invoke()
        secondaryButton.visibility = if (sec == null) View.GONE else View.VISIBLE
        sec?.let { (l, a) -> secondaryButton.text = l; secondaryButton.setOnClickListener { a() } }
    }

    private fun startPolling(check: () -> Unit) {
        stopPolling()
        poll = object : Runnable {
            override fun run() {
                check()
                handler.postDelayed(this, 800)
            }
        }.also { handler.post(it) }
    }

    private fun stopPolling() {
        poll?.let(handler::removeCallbacks)
        poll = null
    }

    private fun finishOnboarding() {
        config.onboarded = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---- pages ----

    private fun column(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(24))
        children.forEach(::addView)
    }

    private fun bold(vararg parts: Pair<String, Boolean>): CharSequence = SpannableStringBuilder().apply {
        parts.forEach { (s, b) ->
            val start = length
            append(s)
            if (b) setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildPages(): List<Page> = buildList {
        add(welcome())
        add(howItWorks())
        add(accessibility())
        add(brain())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(notifications())
        add(chooseMode())
        add(done())
    }

    private fun welcome(): Page {
        val hero = SwipeDemoView(this)
        val view = column(
            hero.apply { layoutParams = lp(8, height = dp(330)) },
            label("autoDate", 15f, Palette.ACCENT, bold = true).apply { gravity = Gravity.CENTER }.mt(20),
            title("Your AI wingman\nfor Hinge").apply { gravity = Gravity.CENTER }.mt(4),
            label(
                "It goes through profiles for you, likes the ones that fit your type, and writes a short comment on the best photo or prompt. Once someone replies, you take it from there.",
                16f, Palette.TEXT_2,
            ).apply { gravity = Gravity.CENTER }.mt(12),
            label("Setup takes about 3 minutes", 13f, Palette.TEXT_2).apply { gravity = Gravity.CENTER }.mt(20),
        )
        return Page("welcome", view, primary = { "Get started" to ::next }, counted = false)
    }

    private fun howItWorks(): Page {
        fun step(kind: StepIllustration.Kind, n: String, head: String, body: String) = row(
            StepIllustration(this, kind).apply { layoutParams = LinearLayout.LayoutParams(dp(112), dp(104)) },
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(n, 12f, Palette.ACCENT, bold = true))
                addView(label(head, 18f, Palette.TEXT, bold = true).mt(2))
                addView(label(body, 14f, Palette.TEXT_2).mt(4))
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) } },
        ).mt(20)

        val view = column(
            title("How it works"),
            label("You tap Start. autoDate opens Hinge and does this for each profile:", 16f, Palette.TEXT_2).mt(8),
            step(StepIllustration.Kind.READ, "1", "Reads the profile", "Scrolls through photos and prompts at a normal human pace."),
            step(StepIllustration.Kind.DECIDE, "2", "Scores the match", "Claude rates it against your preferences. Below your bar, it skips."),
            step(StepIllustration.Kind.WRITE, "3", "Writes a comment", "Likes the best photo or prompt, with a short line that sounds like you."),
            card(
                label(bold("It learns from you. " to true, "Start in Learn mode: you swipe yourself and it watches. When it agrees with you most of the time, let it drive." to false), 14f),
                fill = Palette.ACCENT_SOFT,
            ).mt(24),
        )
        return Page("how", view, primary = { "Next" to ::next })
    }

    private fun accessibility(): Page {
        accessStatus = label("", 15f, Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val restrictedSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        restrictedHelp = card(
            label("Seeing “Restricted setting”?", 17f, Palette.TEXT, bold = true),
            label("Android blocks this for apps that didn't come from the Play Store. You can unblock it:", 14f, Palette.TEXT_2).mt(4),
            SettingsDemoView(this, SettingsDemoView.Kind.RESTRICTED).apply { layoutParams = lp(14, width = minOf(dp(300), resources.displayMetrics.widthPixels - dp(72)), height = dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL } },
            stepLine("1", "Tap Open App info below."),
            stepLine("2", bold("Tap ⋮ in the top corner, then " to false, "Allow restricted settings" to true, ". Confirm with your PIN." to false)),
            stepLine("3", "Come back here and turn autoDate on again."),
            secondary("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.mt(16),
        ).apply { visibility = View.GONE }

        val view = column(
            title("Let autoDate tap\nfor you"),
            label("autoDate uses Android's accessibility feature to read Hinge and tap like a person would. It only works inside Hinge.", 16f, Palette.TEXT_2).mt(8),
            SettingsDemoView(this, SettingsDemoView.Kind.ACCESSIBILITY).apply { layoutParams = lp(20, width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(40)), height = dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL } },
            stepLine("1", bold("Tap " to false, "Open settings" to true, " below." to false), top = 16),
            stepLine("2", bold("Find " to false, "autoDate" to true, " (often under Downloaded apps or Installed apps)." to false)),
            stepLine("3", bold("Turn on " to false, "Use autoDate" to true, ", then tap " to false, "Allow" to true, "." to false)),
            accessStatus.mt(20),
            restrictedHelp.mt(16),
        )
        fun on() = ServiceSwitch.isOn(this)
        fun draw() {
            val ok = on()
            accessStatus.text = if (ok) "✓  autoDate is on" else "Waiting for you to turn it on…"
            accessStatus.setTextColor(if (ok) Palette.GOOD else Palette.TEXT_2)
            accessStatus.background = Palette.rounded(if (ok) Palette.GOOD_SOFT else Palette.SURFACE, dp(16).toFloat(), if (ok) 0 else Palette.BORDER, if (ok) 0 else dp(1))
            if (ok) restrictedHelp.visibility = View.GONE
        }
        var wasOn = false
        return Page(
            "access", view,
            primary = {
                if (on()) "Continue" to ::next
                else "Open settings" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
            secondary = {
                when {
                    on() -> null
                    restrictedSupported && restrictedHelp.visibility != View.VISIBLE -> "Seeing “Restricted setting”?" to {
                        restrictedHelp.visibility = View.VISIBLE
                        refreshButtons()
                        (restrictedHelp.parent?.parent as? ScrollView)?.let { s -> s.post { s.smoothScrollTo(0, restrictedHelp.top) } }
                    }
                    else -> "Skip for now" to ::next
                }
            },
            onShow = {
                ServiceSwitch.restore(this)
                wasOn = on()
                draw()
                startPolling {
                    draw()
                    if (on() != wasOn) { wasOn = on(); refreshButtons() }
                }
            },
        )
    }

    private fun brain(): Page {
        urlField = field("https://your-proxy.example.com", config.proxyUrl)
        tokenField = field("Paste your DEVICE_TOKEN", config.deviceToken, secret = true)
        brainStatus = label("", 14f, Palette.TEXT_2).apply { visibility = View.GONE }
        val paste = quiet("Paste", Palette.ACCENT) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val s = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (s.isNullOrEmpty()) toastStatus("Nothing to paste. Copy the code first.", Palette.BAD) else tokenField.setText(s)
        }.apply { minWidth = 0; minimumWidth = 0; setPadding(dp(14), 0, dp(14), 0) }

        val view = column(
            title("Connect the brain"),
            label("autoDate asks Claude to judge each profile and write the comments, through your own copy of the proxy in the repo's proxy/ folder. It holds your API key, preferences and voice samples.", 16f, Palette.TEXT_2).mt(8),
            card(
                label(bold("No proxy yet? " to true, "Follow proxy/README.md. " to false, "npm run local" to true, " prints the link and access code (the DEVICE_TOKEN). On Cloudflare, the code is the DEVICE_TOKEN you set with wrangler secret put." to false), 14f),
                fill = Palette.ACCENT_SOFT,
            ).mt(20),
            label("Proxy link", 14f, Palette.TEXT, bold = true).mt(22),
            urlField.mt(6),
            row(label("Device token", 14f, Palette.TEXT, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, paste).mt(16),
            tokenField.mt(2),
            brainStatus.mt(12),
        )
        return Page(
            "brain", view,
            primary = { (if (connecting) "Connecting…" else "Connect") to ::connect },
            secondary = { "I'll do this later" to ::next },
        )
    }

    private fun toastStatus(msg: String, color: Int) {
        brainStatus.visibility = View.VISIBLE
        brainStatus.text = msg
        brainStatus.setTextColor(color)
    }

    private fun connect() {
        if (connecting) return
        config.proxyUrl = urlField.text.toString()
        config.deviceToken = tokenField.text.toString()
        urlField.setText(config.proxyUrl)
        if (!config.brainConfigured) return toastStatus("Fill in both the link and the code.", Palette.BAD)
        connecting = true
        refreshButtons()
        toastStatus("Connecting…", Palette.TEXT_2)
        thread {
            val error = runCatching { com.autodate.brain.BrainClient(config).status() }.exceptionOrNull()
            runOnUiThread {
                connecting = false
                if (error == null) {
                    toastStatus("✓  Connected", Palette.GOOD)
                    handler.postDelayed({ if (pages[index].key == "brain") next() }, 700)
                } else {
                    val rejected = error is com.autodate.brain.BrainError && error.status in listOf(401, 403)
                    toastStatus(
                        if (rejected) "The server is there, but it didn't accept that code. Check it was copied in full."
                        else "Couldn't reach the server. Check the link and your internet. (${error.message})",
                        Palette.BAD,
                    )
                }
                refreshButtons()
            }
        }
    }

    private fun notifications(): Page {
        fun granted() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val mock = card(
            row(
                badge("♥\uFE0E", fill = Palette.ACCENT, ink = Palette.SURFACE, size = 36),
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("autoDate · now", 12f, Palette.TEXT_2))
                    addView(label("Session done: 6 likes sent", 15f, Palette.TEXT, bold = true))
                    addView(label("2 new matches got an opener. Your turn!", 14f, Palette.TEXT_2))
                }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) } },
            ),
        ).apply { elevation = dp(6).toFloat() }
        val view = column(
            title("Stay in the loop"),
            label("autoDate tells you when a session ends, when it needs you, and when a match is ready for you to take over.", 16f, Palette.TEXT_2).mt(8),
            mock.mt(28),
        )
        return Page(
            "notify", view,
            primary = { if (granted()) "Continue" to ::next else "Allow notifications" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) } },
            secondary = { if (granted()) null else "Not now" to ::next },
            onShow = {
                mock.translationY = dp(24).toFloat(); mock.alpha = 0f
                mock.animate().translationY(0f).alpha(1f).setStartDelay(150).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
            },
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && pages[index].key == "notify") next()
    }

    private fun chooseMode(): Page {
        val options = listOf(
            Triple(Mode.SHADOW, "Learn", "You swipe like normal. autoDate watches, guesses, and learns your type. Nothing is sent for you."),
            Triple(Mode.APPROVE, "Co-pilot", "autoDate swipes. Before each like, you see its comment and tap send, edit, or skip."),
            Triple(Mode.AUTO, "Autopilot", "autoDate swipes and sends on its own, within your daily limits."),
        )
        if (config.mode !in options.map { it.first }) config.mode = Mode.SHADOW
        fun drawCards() = modeCards.forEach { (m, card) ->
            val on = config.mode == m
            card.background = Palette.ripple(Palette.rounded(if (on) Palette.ACCENT_SOFT else Palette.SURFACE, dp(20).toFloat(), if (on) Palette.ACCENT else Palette.BORDER, dp(if (on) 2 else 1)))
            (card.getChildAt(0) as LinearLayout).getChildAt(2).visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        val view = column(
            title("How hands-on?"),
            label("You can change this any time on Home.", 16f, Palette.TEXT_2).mt(8),
        )
        options.forEachIndexed { i, (mode, name, body) ->
            val card = card(
                row(
                    label(name, 18f, Palette.TEXT, bold = true),
                    label(if (i == 0) "  Start here" else "", 12f, Palette.ACCENT, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                    label("✓", 18f, Palette.ACCENT, bold = true),
                ),
                label(body, 14f, Palette.TEXT_2).mt(4),
            ).apply {
                isClickable = true
                setOnClickListener {
                    config.mode = mode
                    drawCards()
                }
            }
            modeCards[mode] = card
            view.addView(card.mt(if (i == 0) 24 else 12))
        }
        drawCards()
        return Page("mode", view, primary = { "Continue" to ::next })
    }

    private fun done(): Page {
        val view = column(
            CelebrateView(this).apply { layoutParams = lp(0, height = dp(220)) },
            title("You're all set").apply { gravity = Gravity.CENTER },
            label("Here's how a session goes:", 16f, Palette.TEXT_2).apply { gravity = Gravity.CENTER }.mt(6),
            card(
                stepLine("1", bold("Tap the big " to false, "Start" to true, " button on Home. Hinge opens and autoDate takes over." to false), top = 0),
                stepLine("2", bold("A " to false, "floating bubble" to true, " shows what it's doing and how many likes are left. Drag it anywhere." to false), top = 14),
                stepLine("3", bold("To stop, " to false, "tap the bubble or press volume-down." to true) , top = 14),
                stepLine("4", "Check History to see every decision and why.", top = 14),
            ).mt(20),
        )
        return Page("done", view, primary = { "Go to Home" to ::finishOnboarding }, counted = false)
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}
