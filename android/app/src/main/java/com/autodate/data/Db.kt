package com.autodate.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One AI decision as shown on the History tab. [finalAction] is what actually happened (your call in shadow mode). */
data class HistoryRow(
    val ts: Long,
    val name: String,
    val mode: String,
    val aiLike: Boolean,
    val score: Int,
    val reasons: List<String>,
    val comment: String?,
    val finalAction: String?,
    val finalComment: String?,
    val override: Boolean,
)

/** Shadow-mode agreement. [aiLikes] is how often the AI said like; [aiLikesYouLiked] how many of those you liked too (like-precision). */
data class Agreement(val total: Int, val agreed: Int, val aiLikes: Int, val aiLikesYouLiked: Int)

/** Approve-mode comments. A slight edit is a small wording change, not a rewrite. */
data class CommentStats(val sent: Int, val kept: Int, val slight: Int, val rewrite: Int)

/** A saved profile and its offline labels. Physical 0 means unclear; null means not answered yet. */
data class ReviewProfile(
    val key: String,
    val name: String,
    val text: String,
    val actualVerdict: String?,
    var physical: Int?,
    var personality: String?,
    var genuineVerdict: String?,
    var strategyLike: Boolean,
    var note: String?,
)

/** Everything the agent decides and everything you teach it, on the phone only. */
class Db(context: Context) : SQLiteOpenHelper(context, "autodate.db", null, 7) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles(key TEXT PRIMARY KEY, first_seen INTEGER, name TEXT, text TEXT, likeables TEXT)")
        db.execSQL(
            """CREATE TABLE decisions(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_key TEXT, ts INTEGER, mode TEXT,
               model TEXT, score INTEGER, decision TEXT, confidence TEXT, reasons TEXT, like_target INTEGER,
               comment TEXT, flags TEXT, final_action TEXT, final_comment TEXT, human_override INTEGER DEFAULT 0, cost REAL,
               final_target INTEGER, looks INTEGER, attraction INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE labels(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_key TEXT, ts INTEGER, source TEXT,
               verdict TEXT, note TEXT, text TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE matches(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, profile_key TEXT, ts INTEGER,
               opener TEXT, opener_sent_at INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER, ended_at INTEGER, mode TEXT,
               profiles_seen INTEGER DEFAULT 0, likes INTEGER DEFAULT 0, errors INTEGER DEFAULT 0, cost REAL DEFAULT 0, stop_reason TEXT)""",
        )
        createHingeNotifications(db)
        createPendingLikes(db)
        createProfileChanges(db)
        createProfileReviews(db)
    }

    private fun createProfileReviews(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS profile_reviews(profile_key TEXT PRIMARY KEY, ts INTEGER,
           physical INTEGER, personality TEXT, genuine_verdict TEXT, strategy_like INTEGER DEFAULT 0, note TEXT)""",
    )

    private fun createProfileChanges(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS profile_changes(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER,
           what TEXT, before TEXT, after TEXT)""",
    )

    private fun createPendingLikes(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS pending_likes(profile_key TEXT PRIMARY KEY, ts INTEGER, decision_id INTEGER,
           comment TEXT, ai_comment TEXT, target INTEGER, target_label TEXT, ai_like INTEGER)""",
    )

    private fun createHingeNotifications(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS hinge_notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER,
           channel TEXT, category TEXT, title TEXT, text TEXT, action TEXT)""",
    )

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: where the heart actually went, when you moved it on the approval card.
        if (oldVersion < 2) db.execSQL("ALTER TABLE decisions ADD COLUMN final_target INTEGER")
        // 3: Hinge's notifications and whether autoDate kept or removed each one.
        if (oldVersion < 3) createHingeNotifications(db)
        // 4: likes you approved that didn't go out, so the profile isn't judged again when it comes back.
        if (oldVersion < 4) createPendingLikes(db)
        // 5: changes you make to your own Hinge profile, to compare how each one does.
        if (oldVersion < 5) {
            createProfileChanges(db)
            // The two made on Nov 6, 2026, when this page was added.
            val at = now()
            for ((what, before, after) in listOf(
                Triple("Dating intentions", "Long-term relationship, open to short", "Long-term relationship"),
                Triple("Added a note", null, "im looking for a partner in crime to break (bs) societal rules on the weekend :)"),
            )) {
                db.insert("profile_changes", null, ContentValues().apply {
                    put("ts", at); put("what", what); put("before", before); put("after", after)
                })
            }
        }
        // 6: offline review keeps private attraction, personality and strategic likes separate.
        if (oldVersion < 6) createProfileReviews(db)
        // 7: the AI's consensus looks score and its guess at your own attraction score, as numbers.
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE decisions ADD COLUMN looks INTEGER")
            db.execSQL("ALTER TABLE decisions ADD COLUMN attraction INTEGER")
        }
    }

    // --- Hinge notifications --------------------------------------------------------------

    /** Keeps the last 500, enough to check the keep/remove rules against what Hinge really sends. */
    fun logHingeNotification(channel: String?, category: String?, title: String?, text: String?, action: String) {
        val w = writableDatabase
        w.insert("hinge_notifications", null, ContentValues().apply {
            put("ts", now()); put("channel", channel); put("category", category); put("title", title); put("text", text); put("action", action)
        })
        w.execSQL("DELETE FROM hinge_notifications WHERE id <= (SELECT MAX(id) - 500 FROM hinge_notifications)")
    }

    // --- changes to your own profile ---------------------------------------------------------

    /** One change to your Hinge profile. [until] is when the next change went live (null: still the latest). */
    data class ProfileChange(
        val id: Long, val ts: Long, val what: String, val before: String?, val after: String?,
        val until: Long?, val likesSent: Int, val matches: Int,
    )

    fun addProfileChange(what: String, before: String?, after: String?) {
        writableDatabase.insert("profile_changes", null, ContentValues().apply {
            put("ts", now()); put("what", what); put("before", before); put("after", after)
        })
    }

    fun deleteProfileChange(id: Long) {
        writableDatabase.delete("profile_changes", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Newest first, each with what happened while it was the latest change: likes you sent and new
     * matches. Changes made together (within a minute) share one window.
     */
    fun profileChanges(): List<ProfileChange> {
        val rows = readableDatabase.rawQuery("SELECT id, ts, what, before, after FROM profile_changes ORDER BY ts DESC, id DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getLong(1), c.getString(2), c.getStringOrNull(3), c.getStringOrNull(4))) }
        }
        val starts = rows.map { it[1] as Long }.distinct().sortedDescending()
        return rows.map { r ->
            val ts = r[1] as Long
            val until = starts.filter { it > ts + 60_000 }.minOrNull()
            val end = (until ?: Long.MAX_VALUE).toString()
            val likes = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM decisions WHERE final_action = 'like' AND ts >= ? AND ts < ?", arrayOf(ts.toString(), end),
            ).use { it.moveToFirst(); it.getInt(0) }
            val matches = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM matches WHERE ts >= ? AND ts < ?", arrayOf(ts.toString(), end),
            ).use { it.moveToFirst(); it.getInt(0) }
            ProfileChange(r[0] as Long, ts, r[2] as String, r[3] as String?, r[4] as String?, until, likes, matches)
        }
    }

    // --- approved likes that haven't gone out yet --------------------------------------------

    /** What you approved on the card for one profile. [target] and [targetLabel] are the item the heart goes on. */
    data class PendingLike(
        val decisionId: Long, val comment: String?, val aiComment: String?,
        val target: Int?, val targetLabel: String?, val aiLike: Boolean,
    )

    fun savePendingLike(profileKey: String, p: PendingLike) {
        writableDatabase.insertWithOnConflict(
            "pending_likes", null,
            ContentValues().apply {
                put("profile_key", profileKey); put("ts", now()); put("decision_id", p.decisionId)
                put("comment", p.comment); put("ai_comment", p.aiComment); put("target", p.target)
                put("target_label", p.targetLabel); put("ai_like", if (p.aiLike) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Your approval for [profileKey] from the last [maxAgeMs], if its like never went out. */
    fun pendingLike(profileKey: String, maxAgeMs: Long): PendingLike? = readableDatabase.rawQuery(
        "SELECT decision_id, comment, ai_comment, target, target_label, ai_like FROM pending_likes WHERE profile_key = ? AND ts >= ?",
        arrayOf(profileKey, (now() - maxAgeMs).toString()),
    ).use {
        if (!it.moveToFirst()) null
        else PendingLike(
            it.getLong(0), it.getStringOrNull(1), it.getStringOrNull(2),
            if (it.isNull(3)) null else it.getInt(3), it.getStringOrNull(4), it.getInt(5) == 1,
        )
    }

    fun clearPendingLike(profileKey: String) {
        writableDatabase.delete("pending_likes", "profile_key = ?", arrayOf(profileKey))
    }

    // --- profiles -------------------------------------------------------------------------

    fun upsertProfile(key: String, name: String, text: String, likeables: JSONArray) {
        writableDatabase.insertWithOnConflict(
            "profiles", null,
            ContentValues().apply {
                put("key", key); put("first_seen", now()); put("name", name); put("text", text); put("likeables", likeables.toString())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun profileByName(name: String): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT key, text FROM profiles WHERE name = ? ORDER BY first_seen DESC LIMIT 1", arrayOf(name))
            .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }

    // --- offline review -------------------------------------------------------------------

    fun reviewProfiles(): List<ReviewProfile> = readableDatabase.rawQuery(
        """SELECT p.key, COALESCE(p.name, ''), COALESCE(p.text, ''),
                  (SELECT l.verdict FROM labels l WHERE l.profile_key = p.key ORDER BY l.id DESC LIMIT 1),
                  r.physical, r.personality, r.genuine_verdict, COALESCE(r.strategy_like, 0), r.note
           FROM profiles p LEFT JOIN profile_reviews r ON r.profile_key = p.key
           ORDER BY p.first_seen DESC""",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(ReviewProfile(
                    c.getString(0), c.getString(1), c.getString(2), c.getStringOrNull(3),
                    c.getIntOrNull(4), c.getStringOrNull(5), c.getStringOrNull(6), c.getInt(7) == 1, c.getStringOrNull(8),
                ))
            }
        }
    }

    fun saveReview(p: ReviewProfile) {
        writableDatabase.insertWithOnConflict(
            "profile_reviews", null,
            ContentValues().apply {
                put("profile_key", p.key); put("ts", now())
                p.physical?.let { put("physical", it) } ?: putNull("physical")
                put("personality", p.personality); put("genuine_verdict", p.genuineVerdict)
                put("strategy_like", if (p.strategyLike) 1 else 0); put("note", p.note?.trim()?.takeIf { it.isNotEmpty() })
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- decisions ------------------------------------------------------------------------

    fun insertDecision(profileKey: String, mode: String, d: JSONObject): Long = writableDatabase.insert(
        "decisions", null,
        ContentValues().apply {
            put("profile_key", profileKey); put("ts", now()); put("mode", mode)
            put("model", d.optString("model")); put("score", d.optInt("score")); put("decision", d.optString("decision"))
            put("confidence", d.optString("confidence")); put("reasons", d.optJSONArray("reasons")?.toString())
            put("like_target", d.optInt("like_target_index", -1)); put("comment", d.optString("comment").takeIf { !d.isNull("comment") })
            put("flags", d.optJSONArray("flags")?.toString()); put("cost", d.optDouble("cost_usd", 0.0))
            d.optInt("looks", 0).takeIf { it in 1..10 }?.let { put("looks", it) }
            d.optInt("attraction", 0).takeIf { it in 1..10 }?.let { put("attraction", it) }
        },
    )

    /**
     * [finalTarget] is the item the like went on. [aiComment] replaces the stored AI draft when the AI
     * rewrote it for an item you picked, so your edits are measured against the draft you actually saw.
     */
    fun finishDecision(id: Long, action: String, comment: String?, override: Boolean, finalTarget: Int? = null, aiComment: String? = null) {
        writableDatabase.update(
            "decisions",
            ContentValues().apply {
                put("final_action", action); put("final_comment", comment); put("human_override", if (override) 1 else 0)
                finalTarget?.let { put("final_target", it) }
                aiComment?.let { put("comment", it) }
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    /** Likes actually sent since local midnight (counts toward Hinge's daily cap). */
    fun likesToday(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM decisions WHERE ts >= ? AND final_action = 'like'", arrayOf(midnight().toString()),
    ).use { it.moveToFirst(); it.getInt(0) }

    /** Most recent decisions, newest first. [disagreementsOnly] keeps the ones where your call differed from the AI's. */
    fun history(likeThreshold: Int, limit: Int = 200, disagreementsOnly: Boolean = false): List<HistoryRow> {
        // Shadow rows are re-scored at today's cutoff. Other modes acted on their verdict, so they stay as they happened.
        val aiLike = "(d.decision = 'like' AND (d.mode != 'SHADOW' OR d.score >= $likeThreshold))"
        val override = "COALESCE(CASE WHEN d.mode = 'SHADOW' THEN $aiLike != (d.final_action = 'like') ELSE d.human_override END, 0)"
        val where = if (disagreementsOnly) "WHERE $override = 1" else ""
        return readableDatabase.rawQuery(
            """SELECT d.ts, COALESCE(p.name, ''), d.mode, $aiLike, d.score, d.reasons, d.comment, d.final_action, d.final_comment, $override
               FROM decisions d LEFT JOIN profiles p ON p.key = d.profile_key $where
               ORDER BY d.id DESC LIMIT ?""",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val reasons = c.getStringOrNull(5)?.let { r -> runCatching { JSONArray(r) }.getOrNull() }
                    add(
                        HistoryRow(
                            ts = c.getLong(0), name = c.getString(1), mode = c.getString(2),
                            aiLike = c.getInt(3) == 1, score = c.getInt(4),
                            reasons = reasons?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty(),
                            comment = c.getStringOrNull(6), finalAction = c.getStringOrNull(7),
                            finalComment = c.getStringOrNull(8), override = c.getInt(9) == 1,
                        ),
                    )
                }
            }
        }
    }

    /**
     * How often the AI matched you in shadow mode, where every row is your own call. Approve/auto rows
     * are left out: profiles the AI skipped there never reached you, so they'd count as agreement.
     * Worked out from the saved scores at [likeThreshold], so changing the cutoff re-scores past swipes too.
     */
    fun agreement(likeThreshold: Int): Agreement {
        val aiLike = "(decision = 'like' AND score >= $likeThreshold)"
        return readableDatabase.rawQuery(
            """SELECT COUNT(*),
                      COALESCE(SUM($aiLike = (final_action = 'like')), 0),
                      COALESCE(SUM($aiLike), 0),
                      COALESCE(SUM($aiLike AND final_action = 'like'), 0)
               FROM decisions WHERE mode = 'SHADOW' AND final_action IS NOT NULL""",
            null,
        ).use { it.moveToFirst(); Agreement(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
    }

    // --- labels ---------------------------------------------------------------------------

    /** Adds a looks read to the newest label for this profile, keeping any note already there. */
    fun appendLatestLabelNote(profileKey: String, extra: String) {
        val row = readableDatabase.rawQuery(
            "SELECT id, note FROM labels WHERE profile_key = ? ORDER BY id DESC LIMIT 1",
            arrayOf(profileKey),
        ).use { c -> if (!c.moveToFirst()) null else c.getLong(0) to c.getStringOrNull(1) } ?: return
        val merged = listOfNotNull(row.second?.takeIf { it.isNotBlank() }, extra).joinToString("\n")
        writableDatabase.update("labels", ContentValues().apply { put("note", merged) }, "id = ?", arrayOf(row.first.toString()))
    }

    fun insertLabel(profileKey: String, source: String, verdict: String, text: String, note: String? = null) {
        writableDatabase.insert(
            "labels", null,
            ContentValues().apply {
                put("profile_key", profileKey); put("ts", now()); put("source", source)
                put("verdict", verdict); put("note", note); put("text", text)
            },
        )
    }

    fun labelCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM labels", null).use { it.moveToFirst(); it.getInt(0) }

    /**
     * Few-shot set: up to [perSide] likes and skips. Up to [ratedPerSide] of each side are profiles you
     * rated for physical attraction, spread from your lowest to highest scores, so the AI can see your
     * whole scale (their photos are attached by AgentService). The rest are your overrides (where you
     * disagreed with the AI), then the most recent. Returned oldest-first so the prompt order is stable.
     * Rated ones carry "profile_key" and "attraction" for attaching the photo.
     */
    fun examples(perSide: Int = 8, ratedPerSide: Int = 6): JSONArray {
        val out = mutableListOf<JSONObject>()
        for (verdict in listOf("like", "skip")) {
            val rows = mutableListOf<JSONObject>()
            readableDatabase.rawQuery(
                """SELECT l.id, l.text, COALESCE(r.genuine_verdict, l.verdict), l.note, r.physical, r.personality, r.note, l.profile_key
                   FROM labels l LEFT JOIN profile_reviews r ON r.profile_key = l.profile_key
                   WHERE COALESCE(r.genuine_verdict, l.verdict) = ? AND COALESCE(r.strategy_like, 0) = 0
                   ORDER BY (l.source = 'override') DESC, l.id DESC LIMIT 400""",
                arrayOf(verdict),
            ).use { c ->
                while (c.moveToNext()) {
                    val notes = mutableListOf<String>()
                    c.getStringOrNull(3)?.let(notes::add)
                    if (!c.isNull(4)) notes += if (c.getInt(4) == 0) "your physical attraction: unclear" else "your physical attraction: ${c.getInt(4)}/10"
                    c.getStringOrNull(5)?.let { notes += "your personality fit: $it" }
                    c.getStringOrNull(6)?.let { notes += "your type notes: $it" }
                    rows += JSONObject().put("id", c.getLong(0)).put("text", c.getString(1)).put("verdict", c.getString(2))
                        .apply { if (notes.isNotEmpty()) put("note", notes.joinToString("\n")) }
                        .apply { if (!c.isNull(4) && c.getInt(4) > 0) put("attraction", c.getInt(4)).put("profile_key", c.getString(7)) }
                }
            }
            // The same profile can be labeled more than once; keep its first (highest-priority) row.
            val unique = rows.distinctBy { it.optString("profile_key").ifEmpty { "id:" + it.getLong("id") } }
            val rated = unique.filter { it.has("attraction") }.sortedBy { it.getInt("attraction") }
            val picked = spread(rated, ratedPerSide).toMutableList()
            for (r in unique) {
                if (picked.size >= perSide) break
                if (r !in picked) picked += r
            }
            out += picked
        }
        return JSONArray(out.sortedBy { it.getLong("id") }.map { it.apply { remove("id") } })
    }

    /** [n] items evenly spaced through [sorted], ends included. */
    private fun <T> spread(sorted: List<T>, n: Int): List<T> = when {
        sorted.size <= n -> sorted
        n <= 1 -> sorted.take(n)
        else -> (0 until n).map { sorted[it * (sorted.size - 1) / (n - 1)] }.distinct()
    }

    /** Changes whenever you rate or re-rate a profile, so the examples can be refreshed right away. */
    fun reviewsVersion(): Long = readableDatabase.rawQuery(
        "SELECT COUNT(*) * 1000000000000 + COALESCE(MAX(ts), 0) FROM profile_reviews", null,
    ).use { it.moveToFirst(); it.getLong(0) }

    /**
     * Comments you changed before they were sent, oldest first, for the AI to learn from. Blank
     * sends are left out: they can also mean typing failed. Shadow comments from before
     * [shadowSince] are left out too, since older builds often saved them cut off mid-word.
     */
    fun commentStats(): CommentStats {
        val rows = readableDatabase.rawQuery(
            """SELECT comment, final_comment FROM decisions
               WHERE mode = 'APPROVE' AND final_action = 'like' AND final_comment IS NOT NULL AND final_comment != ''""",
            null,
        )
        var kept = 0
        var slight = 0
        var rewrite = 0
        rows.use { c ->
            while (c.moveToNext()) {
                when (commentKind(c.getStringOrNull(0), c.getString(1))) {
                    "kept" -> kept++
                    "slight" -> slight++
                    else -> rewrite++
                }
            }
        }
        return CommentStats(kept + slight + rewrite, kept, slight, rewrite)
    }

    fun commentEdits(shadowSince: Long, limit: Int = 24): JSONArray {
        val heartMoved = "(d.final_target IS NOT NULL AND d.like_target >= 0 AND d.final_target != d.like_target)"
        val rows = readableDatabase.rawQuery(
            """SELECT d.comment, d.final_comment, $heartMoved, d.like_target, d.final_target, p.likeables
               FROM decisions d LEFT JOIN profiles p ON p.key = d.profile_key
               WHERE d.final_action = 'like' AND d.final_comment IS NOT NULL AND d.final_comment != ''
                 AND (d.mode != 'SHADOW' OR d.ts >= ?)
               ORDER BY d.id DESC LIMIT ?""",
            arrayOf(shadowSince.toString(), (limit * 3).toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val sent = c.getString(1)
                    val kind = commentKind(c.getStringOrNull(0), sent)
                    val o = JSONObject().put("kind", kind).put("ai", c.getStringOrNull(0) ?: JSONObject.NULL).put("sent", sent)
                    if (c.getInt(2) == 1) {
                        val items = c.getStringOrNull(5)?.let { runCatching { JSONArray(it) }.getOrNull() }
                        val name = { i: Int -> items?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("index", -1) == i } }
                            ?.let { "${it.optString("kind")}: ${it.optString("label")}" } }
                        val from = name(c.getInt(3))
                        val to = name(c.getInt(4))
                        if (from != null && to != null) o.put("ai_target", from).put("sent_target", to)
                    }
                    add(o)
                }
            }
        }
        val chosen = listOf("rewrite", "slight", "kept").flatMap { kind ->
            rows.filter { it.optString("kind") == kind }.take(8).reversed()
        }
        return JSONArray(chosen)
    }

    /** kept: sent as drafted. slight: punctuation or a few words. rewrite: a different comment. */
    private fun commentKind(ai: String?, sent: String): String {
        if (ai.isNullOrBlank() || ai == sent) return if (ai == sent) "kept" else "rewrite"
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.replace(Regex("\\s+"), " ").trim()
        if (norm(ai) == norm(sent)) return "slight"
        val distance = editDistance(ai, sent)
        return if (distance <= 8 || distance * 5 <= maxOf(ai.length, sent.length)) "slight" else "rewrite"
    }

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }

    // --- matches --------------------------------------------------------------------------

    fun openerSentTo(name: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM matches WHERE name = ? AND opener_sent_at IS NOT NULL LIMIT 1", arrayOf(name),
    ).use { it.moveToFirst() }

    fun recordOpener(name: String, profileKey: String?, opener: String) {
        writableDatabase.insert(
            "matches", null,
            ContentValues().apply { put("name", name); put("profile_key", profileKey); put("ts", now()); put("opener", opener); put("opener_sent_at", now()) },
        )
    }

    fun recentOpeners(limit: Int = 50): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            """SELECT opener FROM matches WHERE opener IS NOT NULL
               UNION ALL SELECT final_comment FROM decisions WHERE final_comment IS NOT NULL
               LIMIT ?""",
            arrayOf(limit.toString()),
        ).use { while (it.moveToNext()) out.put(it.getString(0)) }
        return out
    }

    // --- sessions -------------------------------------------------------------------------

    fun startSession(mode: String): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply { put("started_at", now()); put("mode", mode) })

    fun endSession(id: Long, seen: Int, likes: Int, errors: Int, cost: Double, reason: String) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("ended_at", now()); put("profiles_seen", seen); put("likes", likes); put("errors", errors); put("cost", cost); put("stop_reason", reason)
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    // --- export ---------------------------------------------------------------------------

    /** All tables as JSON, for evals and debugging on a laptop. */
    fun exportJson(): JSONObject {
        val out = JSONObject()
        for (table in listOf("profiles", "decisions", "labels", "profile_reviews", "matches", "sessions", "hinge_notifications")) {
            val rows = JSONArray()
            readableDatabase.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(c.getColumnName(i), c.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> row.put(c.getColumnName(i), c.getString(i))
                        }
                    }
                    rows.put(row)
                }
            }
            out.put(table, rows)
        }
        return out
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.getIntOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private fun now() = System.currentTimeMillis()

    private fun midnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
