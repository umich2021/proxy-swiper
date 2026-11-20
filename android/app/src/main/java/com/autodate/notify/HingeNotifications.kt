package com.autodate.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autodate.data.Db
import com.autodate.screen.HINGE
import org.json.JSONObject

/**
 * Filters Hinge's notifications. Message notifications stay, everything else (likes, matches,
 * promos) is removed as it arrives, and one quiet autoDate notification shows how many messages
 * are unread. Needs notification access (Settings → Hinge notifications). Every Hinge notification
 * is logged on the phone with what was done to it, so the rules can be checked against real ones.
 */
class HingeNotifications : NotificationListenerService() {

    private val db by lazy { Db(this) }
    private val unread by lazy { Unread(this) }

    override fun onListenerConnected() {
        // Apply the rules to whatever Hinge already has up.
        runCatching { activeNotifications }.getOrNull()?.filter { it.packageName == HINGE }?.forEach(::handle)
        Unread.show(this, unread)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == HINGE) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName != HINGE || !isMessage(sbn)) return
        // Tapped, or taken down by Hinge itself (you read the chat in the app): that chat is read.
        // Swiping it away doesn't count as reading it.
        if (reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            unread.clear(conversation(sbn))
            Unread.show(this, unread)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // a bundle header, not a notification of its own
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val action = when {
            isMessage(sbn) -> {
                unread.onMessage(conversation(sbn), messageCount(sbn), text)
                Unread.show(this, unread)
                "kept"
            }
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 -> "ongoing"
            else -> {
                cancelNotification(sbn.key)
                "removed"
            }
        }
        db.logHingeNotification(n.channelId, n.category, title, text, action)
    }

    /**
     * A chat message, judged by every signal Android gives: the message category, the messaging
     * style, or a channel named for messages or chats. Checked against real Hinge notifications
     * once there are some in the log.
     */
    private fun isMessage(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val ex = n.extras
        return n.category == Notification.CATEGORY_MESSAGE ||
            ex.getString(Notification.EXTRA_TEMPLATE)?.contains("MessagingStyle") == true ||
            ex.containsKey(Notification.EXTRA_MESSAGES) ||
            MESSAGE_CHANNEL.containsMatchIn(n.channelId.orEmpty())
    }

    private fun conversation(sbn: StatusBarNotification): String {
        val ex = sbn.notification.extras
        return ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: sbn.key
    }

    /** How many messages a messaging-style notification holds, or null for a plain one. */
    @Suppress("DEPRECATION") // the typed overload needs API 33; minSdk is 30
    private fun messageCount(sbn: StatusBarNotification): Int? =
        sbn.notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size

    private companion object {
        val MESSAGE_CHANNEL = Regex("(?i)message|chat")
    }
}

/** Unread messages per conversation, kept across restarts. */
class Unread(context: Context) {
    private val p = context.getSharedPreferences("hinge_unread", Context.MODE_PRIVATE)

    private fun load(): JSONObject = runCatching { JSONObject(p.getString("chats", "{}")!!) }.getOrElse { JSONObject() }
    private fun save(o: JSONObject) = p.edit().putString("chats", o.toString()).apply()

    /** A messaging-style notification says how many messages it holds; a plain one counts as one new message when its text changes. */
    @Synchronized
    fun onMessage(chat: String, count: Int?, text: String?) {
        val all = load()
        val c = all.optJSONObject(chat) ?: JSONObject().put("n", 0).put("last", "")
        when {
            count != null -> c.put("n", count)
            text != null && text != c.optString("last") -> c.put("n", c.optInt("n") + 1)
        }
        c.put("last", text.orEmpty())
        save(all.put(chat, c))
    }

    @Synchronized
    fun clear(chat: String) = save(load().apply { remove(chat) })

    @Synchronized
    fun clearAll() = save(JSONObject())

    /** Chats with unread messages and how many, most first. */
    @Synchronized
    fun counts(): List<Pair<String, Int>> {
        val all = load()
        return all.keys().asSequence().map { it to all.getJSONObject(it).optInt("n") }.filter { it.second > 0 }
            .sortedByDescending { it.second }.toList()
    }

    companion object {
        private const val CHANNEL = "hinge_unread"
        private const val ID = 4201

        /** Posts or updates the count, or removes it at zero. Silent: Hinge's own message notification is the alert. */
        fun show(context: Context, unread: Unread) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val chats = unread.counts()
            val total = chats.sumOf { it.second }
            if (total == 0) return nm.cancel(ID)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Unread Hinge messages", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(true) },
                )
            }
            val names = chats.joinToString(", ") { (name, n) -> if (n > 1) "$name ($n)" else name }
            val open = context.packageManager.getLaunchIntentForPackage(HINGE)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            // Swiping this away resets the count, in case Hinge never says a chat was read.
            val reset = PendingIntent.getBroadcast(
                context, 0, Intent(context, ClearUnreadReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(if (total == 1) "1 unread message on Hinge" else "$total unread messages on Hinge")
                .setContentText(names)
                .setNumber(total)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setDeleteIntent(reset)
                .build()
            nm.notify(ID, n)
        }

        fun accessGranted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, HingeNotifications::class.java))
    }
}

/** Swiping the unread count away resets it. */
class ClearUnreadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Unread(context).clearAll()
    }
}
