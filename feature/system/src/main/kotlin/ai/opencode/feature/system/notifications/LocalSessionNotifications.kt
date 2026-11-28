package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The notifications the app posts itself, for work the phone is watching: a subagent or a
 * background shell finishing while the app is away.
 *
 * The relay's push covers the session the phone prompted. A subagent runs as its own session with
 * a `parentID`, and the notify plugin never sends for those, so nothing arrived when one finished
 * in the background. These are posted from the transcript the app already has, so they need no
 * relay round trip and no server change.
 */
object LocalSessionNotifications {
    /** One alert per kind of work, replaced in place as more of that kind finishes. */
    private const val SUBAGENT_ID = 4301
    private const val SHELL_ID = 4302

    /** A subagent finished, failed or was stopped while the app was away. */
    @SuppressLint("MissingPermission")
    fun subagent(context: Context, sessionID: String, title: String, serverKey: String?, count: Int = 1) {
        val body = context.resources.getQuantityString(
            R.plurals.system_local_subagent_finished,
            count,
            count,
        )
        post(context, SUBAGENT_ID, "subagent", sessionID, title, body, serverKey, count)
    }

    /** A background shell exited while the app was away. */
    @SuppressLint("MissingPermission")
    fun shell(context: Context, sessionID: String, title: String, command: String, exitCode: Int?, serverKey: String?, count: Int = 1) {
        val body = if (exitCode == null) {
            context.resources.getQuantityString(R.plurals.system_local_shell_finished, count, count)
        } else {
            context.resources.getQuantityString(R.plurals.system_local_shell_finished_exit, count, count, exitCode)
        }
        post(context, SHELL_ID, "shell", sessionID, title, "$body\n$command".trim(), serverKey, count)
    }

    /** Clears both once the app is back in front: the transcript shows what happened. */
    fun clear(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.cancel(SUBAGENT_ID)
            manager.cancel(SHELL_ID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        id: Int,
        tag: String,
        sessionID: String,
        title: String,
        body: String,
        serverKey: String?,
        count: Int,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(count)
            .setOnlyAlertOnce(false)
        // The channel carries the vibration; asking here too keeps it working if the channel's own
        // vibration is ever removed by the platform.
        builder.setVibrate(longArrayOf(0, 120, 90, 120))
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    (sessionID + tag).hashCode(),
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(tag, id, builder.build()) }
            .onFailure { AppLog.app.error { "Local notification not posted: ${it::class.java.simpleName}" } }
    }
}
