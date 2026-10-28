package dev.dimension.flare.data.push

import dev.dimension.flare.model.MicroBlogKey
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

/** Kind of a notification fetched after a push wake-up. */
public enum class PushNotificationType {
    Mention,
    Reply,
    Follow,
    FollowRequest,
    Favourite,
    Reaction,
    Reblog,
    Quote,
    Poll,
    Status,
    Update,
    Other,
}

/** User-facing notification category: one settings toggle and (except FollowRequest) one channel each. */
@Serializable
public enum class PushCategory {
    Mention,
    Follow,
    FollowRequest,
    Favourite,
    Reblog,
    Poll,
    Other,
}

public val PushNotificationType.category: PushCategory
    get() =
        when (this) {
            PushNotificationType.Mention, PushNotificationType.Reply -> PushCategory.Mention
            PushNotificationType.Follow -> PushCategory.Follow
            PushNotificationType.FollowRequest -> PushCategory.FollowRequest
            PushNotificationType.Favourite, PushNotificationType.Reaction -> PushCategory.Favourite
            PushNotificationType.Reblog, PushNotificationType.Quote -> PushCategory.Reblog
            PushNotificationType.Poll -> PushCategory.Poll
            PushNotificationType.Status, PushNotificationType.Update, PushNotificationType.Other -> PushCategory.Other
        }

/** Platform-neutral notification item used to build an Android notification. */
public data class PushNotificationItem(
    val accountKey: MicroBlogKey,
    val notificationId: String,
    val type: PushNotificationType,
    /** Display name, plain text (fallback: username). */
    val actorName: String?,
    /** "@user@host". */
    val actorHandle: String?,
    val actorAvatarUrl: String?,
    /** MicroBlogKey(actor id, account host). */
    val actorKey: MicroBlogKey?,
    /** MicroBlogKey(status/note id, account host). */
    val statusKey: MicroBlogKey?,
    /** Plain-text snippet of the related post, at most 280 chars plus an ellipsis. */
    val text: String?,
    /** Misskey reaction (emoji or :shortcode:), otherwise null. */
    val reaction: String?,
    val createdAtEpochMillis: Long,
)

public sealed interface PushServerKey {
    public data class Available(
        val vapidPublicKey: String,
    ) : PushServerKey

    /** The server has web push turned off or exposes no VAPID key. */
    public data object Disabled : PushServerKey
}

/** The account token cannot manage push subscriptions (HTTP 401/403); the user must sign in again. */
public class PushReauthRequiredException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Implemented by data sources whose server supports Web Push subscriptions. */
@HiddenFromObjC
public interface PushSubscriptionDataSource {
    public suspend fun pushServerKey(): PushServerKey

    /** Throws [PushReauthRequiredException] on HTTP 401/403; other errors propagate. */
    public suspend fun subscribePush(
        endpoint: String,
        p256dh: String,
        auth: String,
        categories: Set<PushCategory>,
    )

    public suspend fun updatePushCategories(categories: Set<PushCategory>)

    /** Swallows 404. */
    public suspend fun unsubscribePush(endpoint: String)

    public suspend fun latestNotificationId(): String?

    /** Newest-first, strictly newer than [sinceId] (all recent when null), never marks anything read. */
    public suspend fun fetchNotificationsSince(
        sinceId: String?,
        limit: Int,
    ): List<PushNotificationItem>
}
