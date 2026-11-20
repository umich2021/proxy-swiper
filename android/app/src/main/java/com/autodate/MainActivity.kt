package com.autodate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.autodate.agent.AgentService
import com.autodate.data.CaptureStore
import com.autodate.data.Db
import com.autodate.screen.HINGE
import com.autodate.ui.Palette
import com.autodate.ui.ReviewPage
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var config: Config
    private val fields = mutableListOf<() -> Unit>() // savers
    private lateinit var servicePill: TextView
    private lateinit var orb: com.autodate.ui.StartOrb
    private lateinit var orbHint: TextView
    private lateinit var modeChips: List<TextView>
    private lateinit var modeSwitch: LinearLayout
    private lateinit var modeBlurb: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupList: LinearLayout
    private lateinit var likesRing: com.autodate.ui.RingStat
    private lateinit var costValue: TextView
    private lateinit var costSub: TextView
    private lateinit var learnedValue: TextView
    private lateinit var capturesInfo: TextView
    private var brainReachable: Boolean? = null
    private var likesCapped = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            drawOrb()
            ticker.postDelayed(this, 1500)
        }
    }
    private lateinit var urlField: EditText
    private lateinit var connection: TextView
    private lateinit var budgetField: EditText
    private lateinit var budgetResult: TextView
    private lateinit var dataResult: TextView
    private lateinit var notifyStatus: TextView
    private lateinit var appInfo: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<Pair<com.autodate.ui.NavIcon, TextView>>
    private var currentTab = 0
    private lateinit var historySummary: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var historyFilter: Button
    private lateinit var changesList: LinearLayout
    private lateinit var reviewPage: ReviewPage
    private var disagreementsOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        // Notification permission is asked for in onboarding, with an explanation first.

        if (!config.onboarded) {
            // Phones set up before the walkthrough existed skip it; it's in Settings → App to replay.
            if (config.brainConfigured) config.onboarded = true
            else {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
        }

        servicePill = text("", 12f, Palette.TEXT, bold = true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
        urlField = textField("http://192.168.1.20:8787", config.proxyUrl) { config.proxyUrl = it }
        connection = text("", 14f, Palette.TEXT_2).apply { visibility = View.GONE; setPadding(dp(2), dp(10), 0, 0) }
        budgetField = EditText(this).apply {
            hint = "3.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            textSize = 15f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_2)
            background = fieldBackground()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setSelectAllOnFocus(true)
        }
        budgetResult = text("", 14f, Palette.TEXT_2).apply { visibility = View.GONE; setPadding(dp(2), dp(10), 0, 0) }
        dataResult = text("", 14f, Palette.TEXT_2).apply { visibility = View.GONE; setPadding(dp(2), dp(10), 0, 0) }
        notifyStatus = text("", 14f, Palette.TEXT).apply { setPadding(dp(2), dp(10), 0, 0) }
        appInfo = text("", 14f, Palette.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        capturesInfo = text("", 14f, Palette.TEXT_2)
        historySummary = text("", 15f, Palette.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyFilter = secondaryButton("") {
            disagreementsOnly = !disagreementsOnly
            loadHistory()
        }

        val home = buildHome()

        val history = page("History", "Notes, then the swipe log").apply {
            profileNotes()?.let { notes ->
                addView(card("Profile notes", text(notes, 14f, Palette.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }))
            }
            addView(collapsibleCard(
                "Show history",
                historySummary,
                historyFilter.withMargin(top = 12),
                historyList,
            ))
        }

        reviewPage = ReviewPage(this)
        val review = page("Review", "Learn your physical type from saved profiles, offline").apply {
            setPadding(paddingLeft, paddingTop, paddingRight, dp(8))
            addView(reviewPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        }

        changesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val changeWhat = plainField("What changed, e.g. Dating intentions")
        val changeBefore = plainField("Before (optional)")
        val changeAfter = plainField("After, or the text you added", multiLine = true)

        val settings = page("Settings", "Tap a section to expand it").apply {
            addView(collapsibleCard(
                "Brain (proxy)",
                fieldLabel("Brain URL"),
                urlField,
                hint("Over USB: http://localhost:8787 (adb reverse). On Wi-Fi: http://<your-pc-ip>:8787"),
                fieldLabel("Device token", top = 16),
                textField("Paste DEVICE_TOKEN", config.deviceToken, secret = true) { config.deviceToken = it },
                secondaryButton("Test connection") { testConnection() }.withMargin(top = 16),
                connection,
                fieldLabel("Daily AI budget (\$)", top = 20),
                budgetField,
                hint("Sessions stop once a day's AI use, counted at API prices, reaches this. The day resets at midnight UTC (4 or 5 PM Pacific). Up to \$50."),
                secondaryButton("Set budget") { setBudget() }.withMargin(top = 12),
                budgetResult,
            ))
            addView(collapsibleCard(
                "Limits",
                numberRow("Likes per day", "Free Hinge allows about 8", config.dailyLikeCap) { config.dailyLikeCap = it },
                divider(),
                numberRow("Profiles per session", null, config.sessionProfileCap) { config.sessionProfileCap = it },
                divider(),
                numberRow("Minutes per session", null, config.sessionMinutes) { config.sessionMinutes = it },
                divider(),
                numberRow("Looks floor", "Skip a visible face under this (1-10). 5 = typical profile. Higher = pickier", config.looksMin) {
                    config.looksMin = it.coerceIn(1, 10)
                },
                divider(),
                numberRow("Like score", "Only like at this AI score or higher (1-100). Higher = pickier", config.likeThreshold) {
                    // Untouched, it keeps following the proxy; once changed, the phone value sticks.
                    if (it != config.likeThreshold || config.myLikeThreshold > 0) config.myLikeThreshold = it
                },
                divider(),
                switchRow("Ask me when unsure", "Off: anything under the like cutoff is skipped", config.askWhenUnsure) { config.askWhenUnsure = it },
                divider(),
                switchRow("Like even if the comment fails", "Off: skips the profile instead of liking without its comment", config.likeIfCommentFails) { config.likeIfCommentFails = it },
                divider(),
                switchRow("Cheap Haiku pre-filter", "Skips obvious no's before the full model", config.prefilter) { config.prefilter = it },
                divider(),
                switchRow("Send openers after a session", "Writes a first message to new matches", config.runInbox) { config.runInbox = it },
                primaryButton("Save settings") {
                    save()
                    toast("Saved")
                    refresh()
                }.withMargin(top = 16),
            ))
            addView(collapsibleCard(
                "Hinge notifications",
                hint("Keeps Hinge's message notifications, removes the rest (likes, matches, promos), and shows how many messages are unread. Leave Hinge's notifications on in Android for this to work."),
                notifyStatus,
                secondaryButton("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.withMargin(top = 8),
            ))
            addView(collapsibleCard(
                "Data",
                capturesInfo,
                secondaryButton("Export data to Downloads") { export() }.withMargin(top = 12),
                secondaryButton("Delete captures & images", danger = true) { confirmDelete() }.withMargin(top = 8),
                dataResult,
            ))
            addView(collapsibleCard(
                "Profile changes",
                changeWhat,
                changeBefore.withMargin(top = 8),
                changeAfter.withMargin(top = 8),
                primaryButton("Add") {
                    val what = changeWhat.text.toString().trim()
                    if (what.isEmpty()) return@primaryButton toast("Say what changed first")
                    val before = changeBefore.text.toString().trim().ifEmpty { null }
                    val after = changeAfter.text.toString().trim().ifEmpty { null }
                    thread {
                        Db(this@MainActivity).use { it.addProfileChange(what, before, after) }
                        runOnUiThread {
                            listOf(changeWhat, changeBefore, changeAfter).forEach { it.text.clear() }
                            loadChanges()
                        }
                    }
                }.withMargin(top = 12),
                hint("Log it when it goes live on Hinge. Each change counts likes sent and new matches until the next one."),
                changesList,
            ))
            addView(collapsibleCard(
                "App",
                appInfo,
                secondaryButton("Replay the setup guide") { openGuide() }.withMargin(top = 12),
                open = true,
            ))
            addView(collapsibleCard("How it works", text(help(), 14f, Palette.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }))
        }

        // One scroll view per tab, so each keeps its scroll position when you switch.
        pages = listOf(home, history, review, settings).mapIndexed { i, column ->
            val width = minOf(resources.displayMetrics.widthPixels, dp(600))
            if (i == TAB_REVIEW) {
                // Not a scroll view: the score and Save & next stay put while the photos scroll.
                android.widget.FrameLayout(this).apply {
                    addView(column, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
                }
            } else {
                ScrollView(this).apply {
                    isFillViewport = true
                    defaultFocusHighlightEnabled = false // otherwise key navigation greys out the whole page
                    addView(column, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
                }
            }
        }
        val content = android.widget.FrameLayout(this).apply { pages.forEach(::addView) }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
            elevation = dp(8).toFloat()
            tabs = TABS.mapIndexed { i, label ->
                val icon = com.autodate.ui.NavIcon(context, com.autodate.ui.NavIcon.Kind.entries[i])
                val name = text(label, 12f, Palette.TEXT_2, bold = true).apply { gravity = Gravity.CENTER }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(10))
                    background = Palette.ripple(Palette.rounded(Palette.SURFACE, 0f))
                    contentDescription = label
                    setOnClickListener { showTab(i) }
                    addView(icon, LinearLayout.LayoutParams(-1, dp(32)))
                    addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                icon to name
            }
        }
        showTab(0)

        // Edge-to-edge: the tab bar's own bottom padding is the nav bar or keyboard, so its background
        // runs to the screen edge instead of leaving a gap under Home.
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                v.setPadding(bars.left, bars.top, bars.right, 0)
                tabBar.setPadding(0, 0, 0, maxOf(bars.bottom, ime))
                android.view.WindowInsets.CONSUMED
            }
        })
    }

    override fun onResume() {
        super.onResume()
        AgentService.appInFront = true
        if (importBrainSettings()) return recreate()
        ticker.post(tick)
        if (ServiceSwitch.restore(this)) toast("Accessibility service turned back on")
        refresh()
        if (currentTab == TAB_HISTORY) loadHistory()
        if (currentTab == TAB_REVIEW) reviewPage.reload()
        if (currentTab == TAB_SETTINGS) loadChanges()
    }

    private fun showTab(i: Int) {
        if (currentTab != i) {
            save() // keep anything typed on Settings
            if (currentTab == TAB_REVIEW) reviewPage.saveNow()
        }
        currentTab = i
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, (icon, name) ->
            icon.active = j == i
            name.setTextColor(if (j == i) Palette.TEXT else Palette.TEXT_2)
        }
        if (i == TAB_HISTORY) loadHistory()
        if (i == TAB_REVIEW) reviewPage.reload()
        if (i == TAB_SETTINGS) loadChanges()
    }

    private fun loadChanges() {
        thread {
            val rows = Db(this).use { it.profileChanges() }
            runOnUiThread {
                changesList.removeAllViews()
                if (rows.isEmpty()) changesList.addView(hint("No changes logged yet.").withMargin(top = 12))
                rows.forEach { changesList.addView(changeCard(it)) }
            }
        }
    }

    private fun changeCard(c: Db.ProfileChange) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Palette.rounded(Palette.SURFACE, dp(14).toFloat(), if (c.until == null) Palette.ACCENT else Palette.BORDER, dp(if (c.until == null) 2 else 1))
        layoutParams = spaced(12)

        val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        val end = c.until ?: System.currentTimeMillis()
        val days = (end - c.ts) / 86_400_000.0
        val span = if (days < 1) "%.0f hours".format(days * 24) else "%.1f days".format(days)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(c.what, 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(if (c.until == null) "running" else "replaced", 12f, if (c.until == null) Palette.ACCENT else Palette.TEXT_2, bold = true))
        })
        addView(text("${fmt.format(java.util.Date(c.ts))} · $span", 12f, Palette.TEXT_2).apply { setPadding(0, dp(2), 0, 0) })
        c.before?.let { addView(text("Before: $it", 14f, Palette.TEXT_2).apply { setPadding(0, dp(8), 0, 0) }) }
        c.after?.let { addView(text(if (c.before != null) "After: $it" else "“$it”", 14f, Palette.TEXT).apply { setPadding(0, dp(4), 0, 0) }) }
        val perDay = if (days >= 1) " · %.1f matches a day".format(c.matches / days) else ""
        addView(text("Since then: ${c.likesSent} likes sent · ${c.matches} new matches$perDay", 14f, Palette.TEXT, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        addView(secondaryButton("Remove", danger = true) {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setMessage("Remove “${c.what}” from the log?")
                .setPositiveButton("Remove") { _, _ -> thread { Db(this@MainActivity).use { it.deleteProfileChange(c.id) }; runOnUiThread { loadChanges() } } }
                .setNegativeButton("Cancel", null)
                .show()
        }.withMargin(top = 10))
    }

    private fun plainField(hint: String, multiLine: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.TEXT_2)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        isSingleLine = !multiLine
        if (multiLine) minLines = 2
        background = fieldBackground()
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun loadHistory() {
        historyFilter.text = if (disagreementsOnly) "Show all" else "Show only disagreements"
        thread {
            val db = Db(this)
            val stats = db.agreement(config.likeThreshold)
            val comments = db.commentStats()
            val rows = db.history(config.likeThreshold, disagreementsOnly = disagreementsOnly)
            db.close()
            runOnUiThread {
                historySummary.text = summaryText(stats, comments)
                historyList.removeAllViews()
                if (rows.isEmpty()) {
                    historyList.addView(hint(if (disagreementsOnly) "No disagreements yet." else "Nothing yet. Swipe in Learn mode and each profile shows up here.").withMargin(top = 12))
                }
                rows.forEach { historyList.addView(historyCard(it)) }
            }
        }
    }

    /**
     * Private notes about your own Hinge profile, from assets/profile_notes.md ("# Heading" lines,
     * then text). That file is in .gitignore, so the card only shows on builds that have it.
     */
    private fun profileNotes(): CharSequence? {
        val raw = runCatching { assets.open("profile_notes.md").bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        return SpannableStringBuilder().apply {
            raw.trim().split(Regex("(?m)^# ")).filter { it.isNotBlank() }.forEachIndexed { i, section ->
                if (i > 0) append("\n\n")
                val heading = section.substringBefore('\n').trim()
                val start = length
                append(heading)
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n").append(section.substringAfter('\n', "").trim())
            }
        }
    }

    private fun summaryText(a: com.autodate.data.Agreement, comments: com.autodate.data.CommentStats): CharSequence {
        val swipe = if (a.total == 0) {
            "No Learn mode swipes yet."
        } else {
            val pct = a.agreed * 100 / a.total
            val precision = if (a.aiLikes > 0) "${a.aiLikesYouLiked * 100 / a.aiLikes}% (${a.aiLikesYouLiked} of ${a.aiLikes})" else "no AI likes yet"
            "AI agrees with you $pct% · ${a.total} Learn mode swipes. When the AI says like, you liked too: $precision."
        }
        val voice = if (comments.sent == 0) {
            "No Co-pilot comments sent yet."
        } else {
            val kept = comments.kept * 100 / comments.sent
            "Comments: ${comments.sent} sent, $kept% unchanged, ${comments.slight} slight edits, ${comments.rewrite} rewrites."
        }
        return twoLine(swipe, voice)
    }

    private fun historyCard(r: com.autodate.data.HistoryRow) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Palette.rounded(Palette.SURFACE, dp(14).toFloat(), if (r.override) Palette.BAD_SOFT else Palette.BORDER, dp(if (r.override) 2 else 1))
        layoutParams = spaced(10)

        val when_ = android.text.format.DateUtils.getRelativeTimeSpanString(r.ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(r.name.ifBlank { "(no name)" }, 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("${(Mode.entries.firstOrNull { it.name == r.mode }?.title ?: r.mode).lowercase()} · $when_", 12f, Palette.TEXT_2))
        })

        val ai = "AI ${if (r.aiLike) "like" else "skip"} ${r.score}"
        val line = when (r.finalAction) {
            null -> "$ai · no final action recorded"
            else -> {
                val who = if (r.mode == Mode.SHADOW.name) "You" else "Result"
                "$ai · $who ${r.finalAction} ${if (r.override) "✗" else "✓"}"
            }
        }
        addView(text(line, 14f, if (r.override) Palette.BAD else Palette.GOOD, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        if (r.reasons.isNotEmpty()) {
            addView(text(r.reasons.joinToString("\n") { "• $it" }, 13f, Palette.TEXT_2).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(dp(1).toFloat(), 1f) })
        }
        r.comment?.let { addView(text("AI comment: “$it”", 13f, Palette.TEXT).apply { setPadding(0, dp(6), 0, 0) }) }
        if (r.finalAction == "like") {
            val sent = r.finalComment?.let { "Sent with comment: “$it”" } ?: "Sent with no comment"
            addView(text(sent, 13f, Palette.TEXT).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun appInfoText(): CharSequence {
        val hinge = runCatching { "Hinge " + packageManager.getPackageInfo(HINGE, 0).versionName }.getOrDefault("Hinge not installed")
        val version = packageManager.getPackageInfo(packageName, 0).versionName + " · " + hinge
        return if (ServiceSwitch.canRestore(this)) {
            twoLine("Version $version", "Accessibility turns itself back on after updates.")
        } else {
            twoLine(
                "Version $version",
                "Android turns accessibility off on some updates. To have it come back on by itself, run once with the phone on USB:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
            )
        }
    }

    /** A tab's column: page title, optional subtitle, then its cards. */
    private fun page(title: String, subtitle: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(32))
        addView(text(title, 28f, Palette.TEXT, bold = true))
        subtitle?.let { addView(text(it, 15f, Palette.TEXT_2).apply { setPadding(0, dp(2), 0, 0) }) }
    }

    /** Typed values are kept even if you leave without tapping Save. */
    override fun onPause() {
        super.onPause()
        AgentService.appInFront = false
        ticker.removeCallbacks(tick)
        save()
        reviewPage.saveNow()
    }

    private fun save() {
        fields.forEach { it() }
        // Show the URL as stored (e.g. with the http:// that Config adds).
        if (urlField.text.toString() != config.proxyUrl) urlField.setText(config.proxyUrl)
    }

    /**
     * Picks up brain.json ({"url": ..., "token": ...}) pushed over USB into the app's files folder,
     * so the long device token doesn't have to be typed. The file is deleted after import.
     */
    private fun importBrainSettings(): Boolean {
        val f = java.io.File(getExternalFilesDir(null), "brain.json")
        if (!f.exists()) return false
        runCatching {
            val o = org.json.JSONObject(f.readText())
            config.proxyUrl = o.getString("url")
            config.deviceToken = o.getString("token")
            toast("Brain settings imported")
        }.onFailure { toast("brain.json unreadable: ${it.message}") }
        f.delete()
        return true
    }

    private fun testConnection() {
        save()
        if (!config.brainConfigured) return showConnection("Enter the brain URL and device token first", Palette.BAD)
        showConnection("Testing…", Palette.TEXT_2)
        thread {
            val (msg, color) = runCatching {
                val s = com.autodate.brain.BrainClient(config).status()
                "✓ Connected (%s). Today: \$%.2f paid, \$%.2f at API prices".format(
                    s.optString("backend", "api"), s.optDouble("spend_today"), s.optDouble("list_today", s.optDouble("spend_today")),
                ) to Palette.GOOD
            }.getOrElse {
                val rejected = it is com.autodate.brain.BrainError && it.status in listOf(401, 403)
                (if (rejected) "Brain reached, but it rejected the device token" else "Can't reach the brain: ${it.message}") to Palette.BAD
            }
            runOnUiThread { showConnection(msg, color) }
        }
    }

    private fun showConnection(msg: String, color: Int) = showResult(connection, msg, color)

    private fun setBudget() {
        save()
        if (!config.brainConfigured) return showResult(budgetResult, "Enter the brain URL and device token first", Palette.BAD)
        val usd = budgetField.text.toString().trim().removePrefix("\$").toDoubleOrNull()
        if (usd == null || usd <= 0) return showResult(budgetResult, "Enter an amount in dollars, like 5", Palette.BAD)
        showResult(budgetResult, "Saving…", Palette.TEXT_2)
        thread {
            val (msg, color) = runCatching {
                val set = com.autodate.brain.BrainClient(config).setBudget(usd).getDouble("max_daily_usd")
                "✓ Daily budget is now \$%.2f".format(set) to Palette.GOOD
            }.getOrElse { "Couldn't set it: ${it.message}" to Palette.BAD }
            runOnUiThread {
                showResult(budgetResult, msg, color)
                refresh()
            }
        }
    }

    private fun showResult(view: TextView, msg: String, color: Int) {
        view.visibility = View.VISIBLE
        view.text = msg
        view.setTextColor(color)
    }

    // ---- Home ----

    private fun buildHome(): LinearLayout {
        modeBlurb = text("", 14f, Palette.TEXT_2).apply { gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f) }
        modeSwitch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = Palette.rounded(Palette.FIELD, dp(22).toFloat(), Palette.BORDER, dp(1))
        }
        modeChips = HOME_MODES.map { m ->
            text(m.title, 14f, Palette.TEXT_2, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(38)
                setOnClickListener {
                    if (config.mode == m) return@setOnClickListener
                    config.mode = m
                    AgentService.instance?.idleStatus()
                    TransitionManager.beginDelayedTransition(modeSwitch.parent as ViewGroup, AutoTransition().apply { duration = 160 })
                    drawModes()
                    drawOrb()
                }
            }.also { modeSwitch.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        orb = com.autodate.ui.StartOrb(this).apply { setOnClickListener { onOrb() } }
        orbHint = text("", 13f, Palette.TEXT_2).apply { gravity = Gravity.CENTER }

        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = Palette.rounded(Palette.ACCENT_SOFT, dp(20).toFloat())
            addView(text("Finish setting up", 17f, Palette.TEXT, bold = true))
            addView(text("You need these before you can start.", 14f, Palette.TEXT_2).apply { setPadding(0, dp(2), 0, dp(4)) })
            addView(setupList)
            layoutParams = spaced(16)
            visibility = View.GONE
        }

        likesRing = com.autodate.ui.RingStat(this)
        costValue = text("–", 20f, Palette.TEXT, bold = true)
        costSub = text("AI cost today", 12f, Palette.TEXT_2)
        learnedValue = text("–", 20f, Palette.TEXT, bold = true)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
            background = Palette.rounded(Palette.SURFACE, dp(24).toFloat(), Palette.BORDER, dp(1))
            layoutParams = spaced(16)
            addView(modeSwitch, LinearLayout.LayoutParams(-1, -2))
            addView(modeBlurb, spaced(12))
            addView(orb, LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(4) })
            addView(orbHint, spaced(0))
        }

        fun tile(vararg children: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = Palette.rounded(Palette.SURFACE, dp(20).toFloat(), Palette.BORDER, dp(1))
            children.forEach(::addView)
        }
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = spaced(12)
            addView(tile(likesRing.apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)) }, text("Likes today", 12f, Palette.TEXT_2).apply { setPadding(0, dp(6), 0, 0) }),
                LinearLayout.LayoutParams(0, -1, 1f))
            addView(tile(costValue.apply { minHeight = dp(56); gravity = Gravity.CENTER }, costSub.apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER }),
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
            addView(tile(learnedValue.apply { minHeight = dp(56); gravity = Gravity.CENTER }, text("Swipes learned", 12f, Palette.TEXT_2).apply { setPadding(0, dp(6), 0, 0) }),
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
        }

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Palette.rounded(Palette.SURFACE, dp(16).toFloat(), Palette.BORDER, dp(1))
            layoutParams = spaced(12)
            addView(text("■", 14f, Palette.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = Palette.rounded(Palette.ACCENT_SOFT, dp(14).toFloat())
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text(twoLine("Stop any time", "Tap the floating bubble, press volume-down, or touch the screen."), 14f, Palette.TEXT, bold = false).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(greeting(), 15f, Palette.TEXT_2))
                    addView(text("autoDate", 28f, Palette.TEXT, bold = true))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(servicePill)
            })
            addView(setupCard)
            addView(hero)
            addView(stats)
            addView(tip)
        }
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun drawModes() {
        HOME_MODES.forEachIndexed { i, m ->
            val on = config.mode == m
            modeChips[i].setTextColor(if (on) Palette.TEXT else Palette.TEXT_2)
            modeChips[i].background = if (on) Palette.rounded(Palette.SURFACE, dp(18).toFloat(), Palette.BORDER, dp(1)) else null
            modeChips[i].elevation = if (on) dp(2).toFloat() else 0f
        }
        modeBlurb.text = config.mode.blurb
    }

    /** What's left before a session can run, as (title, button, action). */
    private fun setupMissing(): List<Triple<String, String, () -> Unit>> = buildList {
        if (packageManager.getLaunchIntentForPackage(HINGE) == null) add(Triple("Install Hinge", "Get it") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$HINGE"))) }
        })
        if (!serviceEnabled()) add(Triple("Turn on autoDate in Accessibility", "Show me") { openGuide("access") })
        if (!config.brainConfigured) add(Triple("Connect your proxy", "Connect") { openGuide("brain") })
        else if (brainReachable == false) add(Triple("Can't reach your proxy", "Fix") { openGuide("brain") })
    }

    private fun openGuide(page: String? = null) {
        startActivity(Intent(this, OnboardingActivity::class.java).apply { page?.let { putExtra(OnboardingActivity.EXTRA_PAGE, it) } })
    }

    private fun drawSetup() {
        val missing = setupMissing()
        setupCard.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        setupList.removeAllViews()
        missing.forEachIndexed { i, (title, action, run) ->
            setupList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                addView(text("${i + 1}", 13f, Palette.ACCENT, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Palette.rounded(Palette.SURFACE, dp(13).toFloat())
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(text(title, 15f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
                addView(button(action, Palette.ACCENT, Palette.SURFACE, 0) { run() }.apply {
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 14f
                    minHeight = dp(38); minimumHeight = dp(38)
                    minWidth = 0; minimumWidth = 0
                    setPadding(dp(16), 0, dp(16), 0)
                    layoutParams = LinearLayout.LayoutParams(-2, dp(38))
                })
            })
        }
    }

    private fun drawOrb() {
        if (!::orb.isInitialized) return
        val running = AgentService.instance?.sessionRunning == true
        val ready = setupMissing().isEmpty()
        when {
            running -> {
                orb.state = com.autodate.ui.StartOrb.State.RUNNING
                orb.label = "Stop"
                orb.sublabel = "Session running"
                orbHint.text = "autoDate is using Hinge right now."
            }
            !ready -> {
                orb.state = com.autodate.ui.StartOrb.State.BLOCKED
                orb.label = "Start"
                orb.sublabel = "Finish setup first"
                orbHint.text = "Finish the steps above, then come back here."
            }
            config.mode.drives && likesCapped -> {
                orb.state = com.autodate.ui.StartOrb.State.BLOCKED
                orb.label = "Out of likes"
                orb.sublabel = "Back at midnight"
                orbHint.text = "Hinge's free likes reset every day. Switch to Learn to keep training it."
            }
            config.mode.drives -> {
                orb.state = com.autodate.ui.StartOrb.State.READY
                orb.label = "Start"
                orb.sublabel = "Opens Hinge"
                orbHint.text = if (config.mode == Mode.APPROVE) "You'll OK each like before it's sent." else "Runs up to ${config.sessionMinutes} min or ${config.dailyLikeCap} likes a day."
            }
            else -> {
                orb.state = com.autodate.ui.StartOrb.State.READY
                orb.label = "Open Hinge"
                orb.sublabel = "${config.mode.title} mode"
                orbHint.text = if (config.mode == Mode.SHADOW) "Swipe like you normally would. It watches and learns." else "Tap the REC bubble on each screen you want saved."
            }
        }
        servicePill.text = when {
            running -> "● Running"
            !ready -> "Setup needed"
            else -> "● Ready"
        }
        val good = running || ready
        servicePill.setTextColor(if (good) Palette.GOOD else Palette.ACCENT)
        servicePill.background = Palette.rounded(if (good) Palette.GOOD_SOFT else Palette.ACCENT_SOFT, dp(12).toFloat())
    }

    private fun onOrb() {
        when (orb.state) {
            com.autodate.ui.StartOrb.State.RUNNING -> {
                AgentService.instance?.stopSession()
                orb.postDelayed(::refresh, 500)
            }
            com.autodate.ui.StartOrb.State.BLOCKED ->
                if (setupMissing().isNotEmpty()) setupMissing().first().third() else toast("Out of likes for today. Hinge resets them at midnight.")
            com.autodate.ui.StartOrb.State.READY -> startSession()
        }
    }

    private fun refresh() {
        notifyStatus.text = if (com.autodate.notify.Unread.accessGranted(this)) "✓ On" else "Off: tap Notification access and turn on autoDate"
        val (files, bytes) = CaptureStore.stats(this)
        capturesInfo.text = "Stored captures: $files files · %.1f MB".format(bytes / 1_048_576.0)
        appInfo.text = appInfoText()
        drawModes()
        drawSetup()
        drawOrb()
        thread {
            val db = Db(this)
            val sent = db.likesToday()
            val labels = db.labelCount()
            db.close()
            runOnUiThread {
                // Co-pilot and Autopilot stop the moment they start once the cap is hit, so the button says so.
                likesCapped = sent >= config.dailyLikeCap
                likesRing.value = "$sent/${config.dailyLikeCap}"
                likesRing.fraction = sent / config.dailyLikeCap.coerceAtLeast(1).toFloat()
                learnedValue.text = labels.toString()
                drawOrb()
            }
        }
        // The proxy keeps the only record of this; the phone just stores what was billed, $0 on the subscription.
        thread {
            val status = if (!config.brainConfigured) null else runCatching { com.autodate.brain.BrainClient(config).status() }.getOrNull()
            status?.optJSONObject("thresholds")?.let {
                // Learn mode has no session start to pick up a changed cutoff, so keep it fresh here.
                config.likeThreshold = it.optInt("like", config.likeThreshold)
                config.askBelow = it.optInt("ask_me_below", config.askBelow)
            }
            runOnUiThread {
                brainReachable = if (config.brainConfigured) status != null else null
                if (status == null) {
                    costValue.text = "–"
                    costSub.text = if (config.brainConfigured) "Proxy offline" else "AI cost today"
                } else {
                    val cap = status.optDouble("max_daily_usd")
                    if (!budgetField.hasFocus() && !cap.isNaN()) budgetField.setText("%.2f".format(cap))
                    costValue.text = "\$%.2f".format(status.optDouble("list_today"))
                    costSub.text = if (cap.isNaN()) "AI cost today" else "of \$%.0f a day".format(cap)
                }
                drawSetup()
                drawOrb()
            }
        }
    }

    private fun startSession() {
        save()
        val hinge = packageManager.getLaunchIntentForPackage(HINGE) ?: return toast("Hinge isn't installed")
        val service = AgentService.instance ?: return toast("Turn on the autoDate accessibility service first")
        // Learn and Record have no session: they watch whenever Hinge is open, so just open it.
        if (config.mode.drives) service.startSession()?.let { return toast(it) }
        else service.idleStatus()
        startActivity(hinge)
    }

    private fun serviceEnabled(): Boolean = ServiceSwitch.isOn(this)

    private fun export() {
        showResult(dataResult, "Exporting…", Palette.TEXT_2)
        thread {
            val (msg, color) = try {
                val db = Db(this)
                val path = CaptureStore.exportZip(this, db.exportJson())
                db.close()
                "✓ Saved to $path" to Palette.GOOD
            } catch (e: Exception) {
                "Export failed: ${e.message}" to Palette.BAD
            }
            runOnUiThread { showResult(dataResult, msg, color) }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete captures & images?")
            .setMessage("Captured screens and saved profile images contain other people's profiles. Your labels and decisions (text) are kept.")
            .setPositiveButton("Delete") { _, _ ->
                CaptureStore.deleteAll(this)
                showResult(dataResult, "✓ Captures and images deleted", Palette.GOOD)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Palette.BAD)
    }

    // ---- views ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(title: String?, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(if (title == null) 8 else 16), dp(16), dp(16))
        background = Palette.rounded(Palette.SURFACE, dp(16).toFloat(), Palette.BORDER, dp(1))
        if (title != null) addView(text(title, 17f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        children.forEach(::addView)
        layoutParams = spaced(16)
    }

    /** A card whose body is hidden until you tap its title. Starts closed unless [open]. */
    private fun collapsibleCard(title: String, vararg children: View, open: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(4))
        background = Palette.rounded(Palette.SURFACE, dp(16).toFloat(), Palette.BORDER, dp(1))
        layoutParams = spaced(16)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (open) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(12))
            children.forEach(::addView)
        }
        val arrow = text("▾", 17f, Palette.TEXT_2).apply { rotation = if (open) 180f else 0f }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            addView(text(title, 17f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow)
            setOnClickListener {
                (parent as? ViewGroup)?.let {
                    TransitionManager.beginDelayedTransition(it, AutoTransition().apply { duration = 180 })
                }
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.animate().rotation(if (open) 180f else 0f).setDuration(180).start()
            }
        })
        addView(body)
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun <T : View> T.withMargin(top: Int) = apply { layoutParams = spaced(top) }

    private fun button(label: String, fill: Int, textColor: Int, stroke: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(textColor)
        stateListAnimator = null
        background = Palette.ripple(Palette.rounded(fill, dp(12).toFloat(), stroke, if (stroke != 0) dp(1) else 0))
        minHeight = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
        layoutParams = spaced(0)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) =
        button(label, Palette.ACCENT, Palette.SURFACE, 0, onClick).apply { typeface = Typeface.DEFAULT_BOLD }

    private fun secondaryButton(label: String, danger: Boolean = false, onClick: () -> Unit) =
        button(label, Palette.SURFACE, if (danger) Palette.BAD else Palette.TEXT, Palette.BORDER, onClick)

    private fun fieldLabel(s: String, top: Int = 0) = text(s, 14f, Palette.TEXT, bold = true).apply { setPadding(0, dp(top), 0, dp(6)) }

    private fun hint(s: String) = text(s, 13f, Palette.TEXT_2).apply { setPadding(dp(2), dp(6), 0, 0) }

    private fun fieldBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Palette.rounded(Palette.SURFACE, dp(10).toFloat(), Palette.ACCENT, dp(2)))
        addState(intArrayOf(), Palette.rounded(Palette.FIELD, dp(10).toFloat(), Palette.BORDER, dp(1)))
    }

    private fun textField(hint: String, value: String, secret: Boolean = false, save: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.TEXT_2)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        typeface = Typeface.DEFAULT // password fields default to monospace
        background = fieldBackground()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        fields += { save(text.toString()) }
    }

    private fun twoLine(title: String, sub: String?): CharSequence = SpannableStringBuilder(title).apply {
        if (sub == null) return@apply
        val start = length
        append("\n").append(sub)
        setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(Palette.TEXT_2), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun rowLabel(title: String, sub: String?) = text(twoLine(title, sub), 15f, Palette.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }

    private fun numberRow(title: String, sub: String?, value: Int, save: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(rowLabel(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val edit = EditText(context).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            textSize = 15f
            setTextColor(Palette.TEXT)
            gravity = Gravity.CENTER
            background = fieldBackground()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setSelectAllOnFocus(true)
        }
        addView(edit, LinearLayout.LayoutParams(dp(72), -2))
        // Blank or 0 isn't a usable limit; keep the old value instead.
        fields += { edit.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let(save) }
    }

    private fun switchRow(title: String, sub: String?, value: Boolean, save: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        val sw = Switch(context).apply { isChecked = value }
        addView(rowLabel(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        addView(sw)
        setOnClickListener { sw.toggle() }
        fields += { save(sw.isChecked) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Palette.BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun help(): CharSequence = SpannableStringBuilder().apply {
        HELP.forEachIndexed { i, (heading, body) ->
            if (i > 0) append("\n\n")
            val start = length
            append(heading)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n").append(body.trimIndent())
        }
    }

    private companion object {
        val TABS = listOf("Home", "History", "Review", "Settings")
        val HOME_MODES = listOf(Mode.SHADOW, Mode.APPROVE, Mode.AUTO, Mode.INSPECT)
        const val TAB_HISTORY = 1
        const val TAB_REVIEW = 2
        const val TAB_SETTINGS = 3

        val HELP = listOf(
            "First time" to """
                Settings → App → Replay the setup guide walks through every step with animations. In short:
                1. Android 13+: Settings → Apps → autoDate → ⋮ → Allow restricted settings.
                2. Accessibility → autoDate → Use autoDate → Allow.
                3. Paste the server link and access code above, then Test connection.
            """,
            "Modes" to """
                • Learn: tap Open Hinge and swipe as usual. Each like/skip you make is saved, and the bubble shows whether the AI agreed. The bubble is red while the screen is being read and green once it has been, so move on when it turns green. Tap the bubble to ask the AI about the profile on screen. See every result on the History tab.
                • Co-pilot: tap Start. The AI drives; each like shows a card to approve, edit or skip.
                • Autopilot: tap Start. The AI drives within your limits.
                • Record: tap Open Hinge and browse; the REC bubble records screens. Export and send for analysis.
            """,
            "Offline review" to """
                The Review tab shows profiles whose screenshots autoDate saved. Rate only your private physical attraction, or Face unclear, and add optional type notes. Your Hinge swipe is not the physical label. Review works offline; nothing is sent to the AI.
            """,
            "Stopping" to """
                Tap the bubble, press volume-down, or touch the screen. The session stops immediately. Tap the bubble again to resume.
            """,
        )
    }
}
