package dev.dimension.flare.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.dimension.flare.MainActivity
import dev.dimension.flare.R
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.category
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds

@Single
internal class PushNotifier(
    @Provided private val context: Context,
) {
    fun ensureChannels() {
        val manager = NotificationManagerCompat.from(context)
        channels.forEach { (id, nameRes) ->
            manager.createNotificationChannel(
                NotificationChannel(id, context.getString(nameRes), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    fun show(items: List<PushNotificationItem>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        items
            .groupBy { it.accountKey }
            .forEach { (accountKey, groupItems) ->
                val group = groupKey(accountKey)
                groupItems.forEach { item ->
                    notify(manager, notificationId(item), buildNotification(item, group))
                }
                if (groupItems.size >= SUMMARY_THRESHOLD) {
                    notify(manager, summaryId(accountKey), buildSummary(accountKey, groupItems, group))
                }
            }
    }

    fun cancelAccount(accountKey: MicroBlogKey) {
        val manager = NotificationManagerCompat.from(context)
        val group = groupKey(accountKey)
        manager.activeNotifications
            .filter { it.notification.group == group }
            .forEach { manager.cancel(it.id) }
    }

    private fun notify(
        manager: NotificationManagerCompat,
        id: Int,
        notification: Notification,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (error: SecurityException) {
            // Permission revoked between the check and the post; drop this notification.
            error.printStackTrace()
        }
    }

    private fun buildNotification(
        item: PushNotificationItem,
        group: String,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, channelFor(item.type.category))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title(item))
                .setGroup(group)
                .setAutoCancel(true)
                .setSubText(item.accountKey.host)
                .setContentIntent(contentIntent(item))
                .setLargeIcon(avatar(item.actorAvatarUrl))
        item.text?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        // 0 means the time is unknown; setWhen(0) would render as 1970.
        if (item.createdAtEpochMillis != 0L) builder.setWhen(item.createdAtEpochMillis)
        return builder.build()
    }

    private fun buildSummary(
        accountKey: MicroBlogKey,
        groupItems: List<PushNotificationItem>,
        group: String,
    ): Notification =
        NotificationCompat
            .Builder(context, channelFor(groupItems.first().type.category))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.push_notif_summary, groupItems.size))
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSubText(accountKey.host)
            .setContentIntent(summaryContentIntent())
            .build()

    private fun title(item: PushNotificationItem): String {
        val actor = item.actorName ?: item.actorHandle ?: context.getString(R.string.push_notif_someone)
        return when (item.type) {
            PushNotificationType.Poll -> {
                context.getString(R.string.push_notif_poll)
            }

            PushNotificationType.Reaction -> {
                context.getString(R.string.push_notif_reaction, actor, item.reaction.orEmpty())
            }

            PushNotificationType.Other -> {
                context.getString(R.string.push_notif_other, actor)
            }

            else -> {
                context.getString(titleRes(item.type), actor)
            }
        }
    }

    private fun contentIntent(item: PushNotificationItem): PendingIntent {
        val statusKey = item.statusKey
        val actorKey = item.actorKey
        val intent =
            when {
                statusKey != null -> {
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data =
                            Uri.parse(
                                DeeplinkRoute
                                    .Status
                                    .Detail(statusKey, AccountType.Specific(item.accountKey))
                                    .toUri(),
                            )
                    }
                }

                actorKey != null -> {
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data =
                            Uri.parse(
                                DeeplinkRoute
                                    .Profile
                                    .User(AccountType.Specific(item.accountKey), actorKey)
                                    .toUri(),
                            )
                    }
                }

                else -> {
                    launchIntent()
                }
            }
        return PendingIntent.getActivity(
            context,
            notificationId(item),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun summaryContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            launchIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun launchIntent(): Intent = Intent(context, MainActivity::class.java)

    private fun avatar(url: String?): Bitmap? {
        if (url == null) return null
        return runBlocking {
            withTimeoutOrNull(ICON_TIMEOUT) {
                runCatching {
                    val loader = SingletonImageLoader.get(context)
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(ICON_SIZE)
                            .allowHardware(false)
                            .build()
                    (loader.execute(request) as? SuccessResult)?.image?.asDrawable(context.resources)?.toBitmap()
                }.getOrNull()
            }
        }
    }

    companion object {
        const val CHANNEL_MENTION = "push_mention"
        const val CHANNEL_FOLLOW = "push_follow"
        const val CHANNEL_FAVOURITE = "push_favourite"
        const val CHANNEL_REBLOG = "push_reblog"
        const val CHANNEL_POLL = "push_poll"
        const val CHANNEL_OTHER = "push_other"

        private const val SUMMARY_THRESHOLD = 2
        private val ICON_TIMEOUT = 5.seconds
        private const val ICON_SIZE = 256

        private val channels: List<Pair<String, Int>> =
            listOf(
                CHANNEL_MENTION to R.string.push_channel_mention,
                CHANNEL_FOLLOW to R.string.push_channel_follow,
                CHANNEL_FAVOURITE to R.string.push_channel_favourite,
                CHANNEL_REBLOG to R.string.push_channel_reblog,
                CHANNEL_POLL to R.string.push_channel_poll,
                CHANNEL_OTHER to R.string.push_channel_other,
            )

        private fun channelFor(category: PushCategory): String =
            when (category) {
                PushCategory.Mention -> CHANNEL_MENTION
                PushCategory.Follow, PushCategory.FollowRequest -> CHANNEL_FOLLOW
                PushCategory.Favourite -> CHANNEL_FAVOURITE
                PushCategory.Reblog -> CHANNEL_REBLOG
                PushCategory.Poll -> CHANNEL_POLL
                PushCategory.Other -> CHANNEL_OTHER
            }

        private fun groupKey(accountKey: MicroBlogKey): String = "push_$accountKey"

        private fun notificationId(item: PushNotificationItem): Int = (item.accountKey.toString() + "/" + item.notificationId).hashCode()

        private fun summaryId(accountKey: MicroBlogKey): Int = (accountKey.toString() + "/summary").hashCode()

        internal fun titleRes(type: PushNotificationType): Int =
            when (type) {
                PushNotificationType.Mention -> R.string.push_notif_mention
                PushNotificationType.Reply -> R.string.push_notif_reply
                PushNotificationType.Follow -> R.string.push_notif_follow
                PushNotificationType.FollowRequest -> R.string.push_notif_follow_request
                PushNotificationType.Favourite -> R.string.push_notif_favourite
                PushNotificationType.Reaction -> R.string.push_notif_reaction
                PushNotificationType.Reblog -> R.string.push_notif_reblog
                PushNotificationType.Quote -> R.string.push_notif_quote
                PushNotificationType.Poll -> R.string.push_notif_poll
                PushNotificationType.Status -> R.string.push_notif_status
                PushNotificationType.Update -> R.string.push_notif_update
                PushNotificationType.Other -> R.string.push_notif_other
            }
    }
}
