package dev.dimension.flare.data.datasource.mastodon

import com.fleeksoft.ksoup.Ksoup
import dev.dimension.flare.data.network.mastodon.api.InstanceResources
import dev.dimension.flare.data.network.mastodon.api.PushResources
import dev.dimension.flare.data.network.mastodon.api.model.Notification
import dev.dimension.flare.data.network.mastodon.api.model.NotificationTypes
import dev.dimension.flare.data.network.mastodon.api.model.Status
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.data.push.PushServerKey
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException

private val ALL_PUSH_ALERTS =
    listOf(
        "mention",
        "status",
        "reblog",
        "follow",
        "follow_request",
        "favourite",
        "poll",
        "update",
        "quote",
    )

private const val MAX_PUSH_TEXT_LENGTH = 280
private const val ELLIPSIS = "…"

/** Wire names of `data[alerts][<name>]` enabled for [categories]; every known alert is present. */
internal fun alertsFor(categories: Set<PushCategory>): Map<String, Boolean> {
    val enabled =
        buildSet {
            if (PushCategory.Mention in categories) add("mention")
            if (PushCategory.Follow in categories) add("follow")
            if (PushCategory.FollowRequest in categories) add("follow_request")
            if (PushCategory.Favourite in categories) add("favourite")
            if (PushCategory.Reblog in categories) {
                add("reblog")
                add("quote")
            }
            if (PushCategory.Poll in categories) add("poll")
            if (PushCategory.Other in categories) {
                add("status")
                add("update")
            }
        }
    return ALL_PUSH_ALERTS.associateWith { it in enabled }
}

internal fun Notification.toPushNotificationItem(accountKey: MicroBlogKey): PushNotificationItem? {
    val notificationId = id ?: return null
    val actor = account
    val acct = actor?.acct
    val host = accountKey.host
    return PushNotificationItem(
        accountKey = accountKey,
        notificationId = notificationId,
        type = notificationType(),
        actorName = actor?.displayName?.takeIf { it.isNotBlank() } ?: actor?.username,
        actorHandle =
            acct?.takeIf { it.isNotBlank() }?.let {
                if (it.contains('@')) "@$it" else "@$it@$host"
            },
        actorAvatarUrl = actor?.avatar,
        actorKey = actor?.id?.let { MicroBlogKey(id = it, host = host) },
        statusKey = status?.id?.let { MicroBlogKey(id = it, host = host) },
        text = status?.pushText(),
        reaction = null,
        createdAtEpochMillis = createdAt?.toEpochMilliseconds() ?: 0L,
    )
}

private fun Notification.notificationType(): PushNotificationType =
    when (type) {
        NotificationTypes.Mention -> PushNotificationType.Mention
        NotificationTypes.Follow -> PushNotificationType.Follow
        NotificationTypes.FollowRequest -> PushNotificationType.FollowRequest
        NotificationTypes.Favourite -> PushNotificationType.Favourite
        NotificationTypes.Reblog -> PushNotificationType.Reblog
        NotificationTypes.Poll -> PushNotificationType.Poll
        NotificationTypes.Status -> PushNotificationType.Status
        NotificationTypes.Update -> PushNotificationType.Update
        null -> PushNotificationType.Other
    }

private fun Status.pushText(): String? {
    val spoilerText = spoilerText?.trim().orEmpty()
    val raw =
        if (spoilerText.isNotBlank()) {
            "CW: $spoilerText"
        } else {
            Ksoup
                .parse(content.orEmpty())
                .body()
                .text()
                .trim()
        }
    if (raw.isEmpty()) return null
    return if (raw.length > MAX_PUSH_TEXT_LENGTH) {
        raw.take(MAX_PUSH_TEXT_LENGTH) + ELLIPSIS
    } else {
        raw
    }
}

internal class MastodonPush(
    private val accountKey: MicroBlogKey,
    private val resources: PushResources,
    private val instance: InstanceResources,
) {
    /**
     * VAPID public key from `GET api/v2/instance` (`configuration.vapid.public_key`), falling back to
     * the v1 instance payload (carries the same field on Pleroma/Akkoma); [PushServerKey.Disabled]
     * when neither exposes a key. v2 lookup errors fall through to the v1 fallback; v1 errors
     * propagate.
     */
    suspend fun pushServerKey(): PushServerKey {
        val v2PublicKey =
            runCatching {
                instance
                    .instance()
                    .configuration
                    ?.vapid
                    ?.publicKey
            }.getOrNull()
        if (!v2PublicKey.isNullOrBlank()) {
            return PushServerKey.Available(v2PublicKey)
        }
        val v1PublicKey =
            instance
                .instanceV1()
                .configuration
                ?.vapid
                ?.publicKey
        if (!v1PublicKey.isNullOrBlank()) {
            return PushServerKey.Available(v1PublicKey)
        }
        return PushServerKey.Disabled
    }

    suspend fun subscribePush(
        endpoint: String,
        p256dh: String,
        auth: String,
        categories: Set<PushCategory>,
    ) {
        val alerts = alertsFor(categories)
        withReauthMapping {
            resources.subscribePush(
                endpoint = endpoint,
                p256dh = p256dh,
                auth = auth,
                standard = true,
                policy = "all",
                mentionAlert = alerts.getValue("mention"),
                statusAlert = alerts.getValue("status"),
                reblogAlert = alerts.getValue("reblog"),
                followAlert = alerts.getValue("follow"),
                followRequestAlert = alerts.getValue("follow_request"),
                favouriteAlert = alerts.getValue("favourite"),
                pollAlert = alerts.getValue("poll"),
                updateAlert = alerts.getValue("update"),
                quoteAlert = alerts.getValue("quote"),
            )
        }
    }

    suspend fun updatePushCategories(categories: Set<PushCategory>) {
        val alerts = alertsFor(categories)
        resources.updatePushSubscription(
            policy = "all",
            mentionAlert = alerts.getValue("mention"),
            statusAlert = alerts.getValue("status"),
            reblogAlert = alerts.getValue("reblog"),
            followAlert = alerts.getValue("follow"),
            followRequestAlert = alerts.getValue("follow_request"),
            favouriteAlert = alerts.getValue("favourite"),
            pollAlert = alerts.getValue("poll"),
            updateAlert = alerts.getValue("update"),
            quoteAlert = alerts.getValue("quote"),
        )
    }

    /** Mastodon keeps one server subscription per token, so [endpoint] is ignored here. */
    suspend fun unsubscribePush(endpoint: String) {
        try {
            resources.deletePushSubscription()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseException) {
            if (error.response.status.value == 404) {
                return
            }
            throw error
        }
    }

    private suspend fun <T> withReauthMapping(block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseException) {
            if (error.response.status.value == 401 || error.response.status.value == 403) {
                throw PushReauthRequiredException(error.message, error)
            }
            throw error
        }
}
