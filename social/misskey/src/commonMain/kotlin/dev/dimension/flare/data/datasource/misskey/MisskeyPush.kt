package dev.dimension.flare.data.datasource.misskey

import dev.dimension.flare.data.network.misskey.api.AccountApi
import dev.dimension.flare.data.network.misskey.api.MetaApi
import dev.dimension.flare.data.network.misskey.api.model.EndpointRequest
import dev.dimension.flare.data.network.misskey.api.model.INotificationsRequest
import dev.dimension.flare.data.network.misskey.api.model.MetaRequest
import dev.dimension.flare.data.network.misskey.api.model.MisskeyException
import dev.dimension.flare.data.network.misskey.api.model.Note
import dev.dimension.flare.data.network.misskey.api.model.Notification
import dev.dimension.flare.data.network.misskey.api.model.SwRegisterRequest
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.data.push.PushServerKey
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

private const val MAX_PUSH_TEXT_LENGTH = 280
private const val ELLIPSIS = "…"

/** Misskey error codes meaning the token cannot be used for sw/register. */
private val REAUTH_ERROR_CODES = setOf("PERMISSION_DENIED", "AUTHENTICATION_FAILED", "CREDENTIAL_REQUIRED")

/**
 * Wire `type` of `i/notifications` → push type. Misskey has no favourite/update kinds; anything not
 * mapped here (achievementEarned, app, followRequestAccepted, roleAssigned, test, …) is Other, which
 * the client-side category filter keeps behind the "Other activity" toggle.
 */
internal fun misskeyNotificationType(type: String): PushNotificationType =
    when (type) {
        "mention" -> PushNotificationType.Mention
        "reply" -> PushNotificationType.Reply
        "follow" -> PushNotificationType.Follow
        "receiveFollowRequest" -> PushNotificationType.FollowRequest
        "reaction" -> PushNotificationType.Reaction
        "renote" -> PushNotificationType.Reblog
        "quote" -> PushNotificationType.Quote
        "pollEnded", "pollVote" -> PushNotificationType.Poll
        "note" -> PushNotificationType.Status
        else -> PushNotificationType.Other
    }

internal fun Notification.toPushNotificationItem(accountKey: MicroBlogKey): PushNotificationItem? {
    val actor = user
    val host = accountKey.host
    return PushNotificationItem(
        accountKey = accountKey,
        notificationId = id,
        type = misskeyNotificationType(type),
        actorName = actor?.name?.takeIf { it.isNotBlank() } ?: actor?.username,
        actorHandle = actor?.let { "@" + it.username + "@" + (it.host ?: host) },
        actorAvatarUrl = actor?.avatarUrl,
        actorKey = actor?.id?.let { MicroBlogKey(id = it, host = host) },
        statusKey = note?.id?.let { MicroBlogKey(id = it, host = host) },
        text = note?.pushText(),
        reaction = reaction,
        createdAtEpochMillis =
            runCatching {
                Instant.parse(createdAt).toEpochMilliseconds()
            }.getOrDefault(0L),
    )
}

/** Misskey note text is already plain text (MFM source); a content warning replaces it. */
private fun Note.pushText(): String? {
    val contentWarning = cw?.trim().orEmpty()
    val raw =
        if (contentWarning.isNotEmpty()) {
            "CW: $contentWarning"
        } else {
            text?.trim().orEmpty()
        }
    if (raw.isEmpty()) return null
    return if (raw.length > MAX_PUSH_TEXT_LENGTH) {
        raw.take(MAX_PUSH_TEXT_LENGTH) + ELLIPSIS
    } else {
        raw
    }
}

internal class MisskeyPush(
    private val accountKey: MicroBlogKey,
    private val accountApi: AccountApi,
    private val metaApi: MetaApi,
) {
    /**
     * VAPID public key from `POST meta` (`swPublickey`); [PushServerKey.Disabled] when service-worker
     * push is off or the key is missing.
     */
    suspend fun pushServerKey(): PushServerKey {
        val meta = metaApi.meta(MetaRequest())
        val publicKey = meta.swPublickey
        return if (meta.enableServiceWorker == true && !publicKey.isNullOrBlank()) {
            PushServerKey.Available(publicKey)
        } else {
            PushServerKey.Disabled
        }
    }

    /**
     * Misskey decrypts the push payload server-side, so the Web Push keys come straight from the
     * distributor; categories are filtered client-side and never sent to the server.
     */
    suspend fun subscribePush(
        endpoint: String,
        p256dh: String,
        auth: String,
        categories: Set<PushCategory>,
    ) {
        if (!endpoint.startsWith("https://")) {
            throw IllegalArgumentException("Misskey requires an https push endpoint")
        }
        withReauthMapping {
            accountApi.swRegister(
                SwRegisterRequest(
                    endpoint = endpoint,
                    auth = auth,
                    publickey = p256dh,
                    sendReadMessage = false,
                ),
            )
        }
    }

    /** Misskey has no server-side alert settings; categories are filtered client-side. */
    suspend fun updatePushCategories(categories: Set<PushCategory>) {
    }

    suspend fun unsubscribePush(endpoint: String) {
        try {
            accountApi.swUnregister(EndpointRequest(endpoint))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseException) {
            if (error.response.status.value == 404) {
                return
            }
            throw error
        }
    }

    suspend fun latestNotificationId(): String? =
        accountApi
            .iNotifications(
                INotificationsRequest(
                    limit = 1,
                    markAsRead = false,
                ),
            ).firstOrNull()
            ?.id

    suspend fun fetchNotificationsSince(
        sinceId: String?,
        limit: Int,
    ): List<PushNotificationItem> =
        accountApi
            .iNotifications(
                INotificationsRequest(
                    limit = limit,
                    sinceId = sinceId,
                    markAsRead = false,
                ),
            ).mapNotNull { it.toPushNotificationItem(accountKey) }

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
        } catch (error: RequireReLoginException) {
            // MisskeyService's validator turns PERMISSION_DENIED bodies into this.
            throw PushReauthRequiredException(error.message, error)
        } catch (error: MisskeyException) {
            // Real servers send {"error": {"code": ...}} bodies, which the validator rethrows.
            if (error.error?.code in REAUTH_ERROR_CODES) {
                throw PushReauthRequiredException(error.message, error)
            }
            throw error
        }
}
