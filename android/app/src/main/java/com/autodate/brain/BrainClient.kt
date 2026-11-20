package com.autodate.brain

import com.autodate.Config
import com.autodate.screen.ProfileSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BrainError(val status: Int, message: String) : Exception(message)

data class Decision(
    val like: Boolean,
    val score: Int,
    val confidence: String,
    val reasons: List<String>,
    val dealbreakers: List<String>,
    val targetIndex: Int,
    val comment: String?,
    /** What the comment is going for: react, curious, riff, plan or shared. */
    val goal: String?,
    val flags: List<String>,
    val costUsd: Double,
    /** What the same call costs at API list price. Equals [costUsd] except on the subscription backend, where costUsd is 0. */
    val listUsd: Double,
    val spendToday: Double,
    val raw: JSONObject,
) {
    companion object {
        fun from(o: JSONObject) = Decision(
            like = o.optString("decision") == "like",
            score = o.optInt("score"),
            confidence = o.optString("confidence", "low"),
            reasons = o.optJSONArray("reasons").strings(),
            dealbreakers = o.optJSONArray("dealbreakers_hit").strings(),
            targetIndex = o.optInt("like_target_index", -1),
            comment = o.optString("comment").takeIf { !o.isNull("comment") && it.isNotBlank() },
            goal = o.optString("comment_goal").takeIf { !o.isNull("comment_goal") && it.isNotBlank() },
            flags = o.optJSONArray("flags").strings(),
            costUsd = o.optDouble("cost_usd", 0.0),
            listUsd = o.optDouble("list_usd", o.optDouble("cost_usd", 0.0)),
            spendToday = o.optDouble("spend_today", 0.0),
            raw = o,
        )
    }

    /** Prior visual read, stored on the label so later examples can teach type. Null if the judge didn't rate looks. */
    fun looksNote(): String? {
        val n = raw.optInt("looks", 0)
        if (n !in 1..10) return null
        val read = raw.optString("type_read").trim()
        val unclear = if (raw.optString("looks_confidence") == "low") ", face unclear" else ""
        return "looks $n/10$unclear" + if (read.isNotEmpty()) ": $read" else ""
    }
}

/** A new comment for the item the user moved the heart to. [text] is null when none could be written; [problem] says why. */
data class Rewrite(val text: String?, val problem: String?, val costUsd: Double, val listUsd: Double)

data class Opener(val text: String?, val problem: String?, val costUsd: Double)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** Talks to the proxy (proxy/src/worker.ts), which holds the API key, prompts and daily budget. */
class BrainClient(private val config: Config) {

    fun judge(profile: ProfileSnapshot, examples: JSONArray, commentEdits: JSONArray): Decision {
        val body = JSONObject()
            .put("profile", profile.toBrainJson())
            .put("examples", examples)
            .put("comment_edits", commentEdits)
            .put("prefilter", config.prefilter)
            .put("looks_min", config.looksMin)
        return Decision.from(post("/v1/judge", body))
    }

    fun comment(profile: ProfileSnapshot, targetIndex: Int, commentEdits: JSONArray): Rewrite {
        val body = JSONObject()
            .put("profile", profile.toBrainJson())
            .put("target_index", targetIndex)
            .put("comment_edits", commentEdits)
        val o = post("/v1/comment", body)
        return Rewrite(
            o.optString("comment").takeIf { !o.isNull("comment") && it.isNotBlank() },
            o.optString("problem").takeIf { !o.isNull("problem") && it.isNotBlank() },
            o.optDouble("cost_usd", 0.0),
            o.optDouble("list_usd", o.optDouble("cost_usd", 0.0)),
        )
    }

    fun opener(profile: ProfileSnapshot, chat: List<String>, recent: JSONArray): Opener {
        val body = JSONObject()
            .put("profile", profile.toBrainJson())
            .put("chat", JSONArray(chat))
            .put("recent_openers", recent)
        val o = post("/v1/opener", body)
        return Opener(o.optString("opener").takeIf { !o.isNull("opener") && it.isNotBlank() }, o.optString("problem").takeIf { !o.isNull("problem") }, o.optDouble("cost_usd", 0.0))
    }

    /** Today's spend and the thresholds from preferences.json. */
    fun status(): JSONObject = request("GET", "/v1/spend", null)

    /** Sets the proxy's daily AI budget (API-price dollars). Works even after today's budget is used up. */
    fun setBudget(usd: Double): JSONObject = request("POST", "/v1/budget", JSONObject().put("max_daily_usd", usd))

    private fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        if (!config.brainConfigured) throw BrainError(0, "proxy URL / device token not set")
        val conn = URL(config.proxyUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("authorization", "Bearer ${config.deviceToken}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text.take(200)) }
            if (code !in 200..299) throw BrainError(code, json.optString("error", "HTTP $code"))
            return json
        } finally {
            conn.disconnect()
        }
    }
}
