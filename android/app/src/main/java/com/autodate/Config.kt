package com.autodate

import android.content.Context

/** [drives]: the AI taps Hinge in a session. Inspect and Shadow only watch while you use Hinge. */
enum class Mode(val title: String, val blurb: String, val drives: Boolean) {
    INSPECT("Record", "Debug only. Saves Hinge's screens when you tap the REC bubble.", false),
    SHADOW("Learn", "You swipe like normal. It watches and learns your type.", false),
    APPROVE("Co-pilot", "It swipes. You OK each like and comment before it's sent.", true),
    AUTO("Autopilot", "It swipes and sends on its own, within your limits.", true),
}

class Config(context: Context) {
    private val p = context.getSharedPreferences("autodate", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: Mode.SHADOW
        set(v) = p.edit().putString("mode", v.name).apply()

    var proxyUrl: String
        get() = p.getString("proxy_url", "").orEmpty()
        set(v) = p.edit().putString("proxy_url", v.trim().trimEnd('/').let { if (it.isEmpty() || "://" in it) it else "http://$it" }).apply()

    var deviceToken: String
        get() = p.getString("device_token", "").orEmpty()
        set(v) = p.edit().putString("device_token", v.trim()).apply()

    var dailyLikeCap: Int
        get() = p.getInt("daily_like_cap", 8)
        set(v) = p.edit().putInt("daily_like_cap", v).apply()

    var sessionProfileCap: Int
        get() = p.getInt("session_profile_cap", 40)
        set(v) = p.edit().putInt("session_profile_cap", v).apply()

    var sessionMinutes: Int
        get() = p.getInt("session_minutes", 30)
        set(v) = p.edit().putInt("session_minutes", v).apply()

    var prefilter: Boolean
        get() = p.getBoolean("prefilter", true)
        set(v) = p.edit().putBoolean("prefilter", v).apply()

    var runInbox: Boolean
        get() = p.getBoolean("run_inbox", true)
        set(v) = p.edit().putBoolean("run_inbox", v).apply()

    /** Approve mode: show a card for profiles between ask_me_below and the like cutoff. Off: those are skipped, as in Auto. */
    var askWhenUnsure: Boolean
        get() = p.getBoolean("ask_when_unsure", false)
        set(v) = p.edit().putBoolean("ask_when_unsure", v).apply()

    var autoCapture: Boolean
        get() = p.getBoolean("auto_capture", true)
        set(v) = p.edit().putBoolean("auto_capture", v).apply()

    /**
     * When this phone first ran a build whose Shadow mode reads the whole comment. Shadow comments
     * from before then were often cut off mid-word, so they're kept out of the AI's examples.
     */
    var shadowCommentsSince: Long
        get() = p.getLong("shadow_comments_since", 0L)
        set(v) = p.edit().putLong("shadow_comments_since", v).apply()

    // Where you dragged the bubble, as window offsets in pixels. Null until it's first moved.
    var bubblePosition: Pair<Int, Int>?
        get() = if (p.contains("bubble_x")) p.getInt("bubble_x", 0) to p.getInt("bubble_y", 0) else null
        set(v) = p.edit().putInt("bubble_x", v?.first ?: 0).putInt("bubble_y", v?.second ?: 0).apply()

    /** When a comment can't be typed: true sends the like anyway, false skips the profile. */
    var likeIfCommentFails: Boolean
        get() = p.getBoolean("like_if_comment_fails", true)
        set(v) = p.edit().putBoolean("like_if_comment_fails", v).apply()

    // Thresholds live in the proxy's preferences.json; cached here at session start.
    // A like score set on the phone (Settings → Limits) wins over the proxy's.
    var likeThreshold: Int
        get() = myLikeThreshold.takeIf { it > 0 } ?: p.getInt("like_threshold", 70)
        set(v) = p.edit().putInt("like_threshold", v).apply()

    /** The like score you set in Settings, 1-100. 0 means use the proxy's value. */
    var myLikeThreshold: Int
        get() = p.getInt("like_threshold_user", 0)
        set(v) = p.edit().putInt("like_threshold_user", v.coerceIn(0, 100)).apply()

    /** Looks floor, 1-10. A face scored under this is skipped. 0 means the default, 5. */
    var looksMin: Int
        get() = p.getInt("looks_min", 5).coerceIn(1, 10)
        set(v) = p.edit().putInt("looks_min", v.coerceIn(1, 10)).apply()

    var askBelow: Int
        get() = p.getInt("ask_below", 55)
        set(v) = p.edit().putInt("ask_below", v).apply()

    // Few-shot examples are frozen for a day so the prompt prefix stays cached.
    var examplesJson: String
        get() = p.getString("examples_json", "[]").orEmpty()
        set(v) = p.edit().putString("examples_json", v).apply()

    var examplesAt: Long
        get() = p.getLong("examples_at", 0)
        set(v) = p.edit().putLong("examples_at", v).apply()

    /** Db.reviewsVersion() when the examples were last built; a new rating rebuilds them. */
    var examplesReviews: Long
        get() = p.getLong("examples_reviews", 0)
        set(v) = p.edit().putLong("examples_reviews", v).apply()

    /** The first-run walkthrough (OnboardingActivity) has been finished. */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    val brainConfigured get() = proxyUrl.isNotBlank() && deviceToken.isNotBlank()
}
