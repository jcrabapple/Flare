package dev.dimension.flare.data.datasource.misskey

import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.data.network.misskey.api.model.Notification
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.model.MicroBlogKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ACCOUNT_KEY = MicroBlogKey(id = "1", host = "misskey.social")

// Fixtures follow the Misskey notification shape from the misskey-hub API docs (endpoint `i/notifications`).
private val MENTION_JSON =
    """
    {
      "id": "8f1e2d3c4a5b6c7d8e9f0a1b",
      "createdAt": "2023-02-17T19:30:00.000Z",
      "type": "mention",
      "user": {
        "id": "7a1b2c3d4e5f6a7b8c9d0e1f",
        "username": "testuser",
        "host": "misskey.example",
        "name": "Test User",
        "avatarUrl": "https://misskey.example/avatar.webp"
      },
      "note": {
        "id": "8a2b3c4d5e6f7a8b9c0d1e2f",
        "createdAt": "2023-02-17T19:29:55.000Z",
        "text": "@flare hello from the docs!",
        "userId": "7a1b2c3d4e5f6a7b8c9d0e1f",
        "user": { "id": "7a1b2c3d4e5f6a7b8c9d0e1f", "username": "testuser" },
        "visibility": "public",
        "reactions": {},
        "renoteCount": 0,
        "repliesCount": 0
      }
    }
    """.trimIndent()

private val REACTION_JSON =
    """
    {
      "id": "8f1e2d3c4a5b6c7d8e9f0a2c",
      "createdAt": "2023-02-17T19:31:00.000Z",
      "type": "reaction",
      "reaction": ":blobcatcool:",
      "user": {
        "id": "7a1b2c3d4e5f6a7b8c9d0e2f",
        "username": "reactor",
        "name": "Reactor"
      },
      "note": {
        "id": "8a2b3c4d5e6f7a8b9c0d1e3f",
        "createdAt": "2023-02-17T19:00:00.000Z",
        "text": "my note about cats",
        "userId": "me",
        "user": { "id": "me", "username": "flare" },
        "visibility": "home",
        "reactions": { ":blobcatcool:": 1 },
        "renoteCount": 0,
        "repliesCount": 0
      }
    }
    """.trimIndent()

private val FOLLOW_JSON =
    """
    {
      "id": "8f1e2d3c4a5b6c7d8e9f0a3d",
      "createdAt": "2023-02-17T19:33:00.000Z",
      "type": "follow",
      "user": {
        "id": "7a1b2c3d4e5f6a7b8c9d0e3f",
        "username": "newfollower",
        "name": "New Follower"
      }
    }
    """.trimIndent()

private val UNKNOWN_TYPE_JSON =
    """
    {
      "id": "8f1e2d3c4a5b6c7d8e9f0a4e",
      "createdAt": "2023-02-17T19:36:00.000Z",
      "type": "achievementEarned",
      "achievement": "myNote Favorited1",
      "user": {
        "id": "7a1b2c3d4e5f6a7b8c9d0e4f",
        "username": "system"
      }
    }
    """.trimIndent()

class MisskeyPushTest {
    @Test
    fun mentionMapsNoteTextActorAndKeys() {
        val notification = MENTION_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("8f1e2d3c4a5b6c7d8e9f0a1b", item.notificationId)
        assertEquals(PushNotificationType.Mention, item.type)
        assertEquals("@flare hello from the docs!", item.text)
        assertEquals("Test User", item.actorName)
        assertEquals("@testuser@misskey.example", item.actorHandle)
        assertEquals("https://misskey.example/avatar.webp", item.actorAvatarUrl)
        assertEquals(
            MicroBlogKey(id = "7a1b2c3d4e5f6a7b8c9d0e1f", host = "misskey.social"),
            item.actorKey,
        )
        assertEquals(
            MicroBlogKey(id = "8a2b3c4d5e6f7a8b9c0d1e2f", host = "misskey.social"),
            item.statusKey,
        )
        assertEquals(ACCOUNT_KEY, item.accountKey)
        assertEquals(1676662200000L, item.createdAtEpochMillis)
        assertNull(item.reaction)
    }

    @Test
    fun reactionNotificationCarriesReactionAndMapsToReaction() {
        val notification = REACTION_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Reaction, item.type)
        assertEquals(":blobcatcool:", item.reaction)
        assertEquals("my note about cats", item.text)
    }

    @Test
    fun followNotificationHasNoStatusOrText() {
        val notification = FOLLOW_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Follow, item.type)
        assertNull(item.text)
        assertNull(item.statusKey)
        assertNull(item.reaction)
        assertEquals("New Follower", item.actorName)
        assertEquals("@newfollower@misskey.social", item.actorHandle)
        assertEquals(
            MicroBlogKey(id = "7a1b2c3d4e5f6a7b8c9d0e3f", host = "misskey.social"),
            item.actorKey,
        )
    }

    @Test
    fun unknownTypeMapsToOther() {
        val notification = UNKNOWN_TYPE_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Other, item.type)
    }

    @Test
    fun allKnownMisskeyTypesMap() {
        val expected =
            mapOf(
                "mention" to PushNotificationType.Mention,
                "reply" to PushNotificationType.Reply,
                "follow" to PushNotificationType.Follow,
                "receiveFollowRequest" to PushNotificationType.FollowRequest,
                "reaction" to PushNotificationType.Reaction,
                "renote" to PushNotificationType.Reblog,
                "quote" to PushNotificationType.Quote,
                "pollEnded" to PushNotificationType.Poll,
                "pollVote" to PushNotificationType.Poll,
                "note" to PushNotificationType.Status,
                "app" to PushNotificationType.Other,
                "followRequestAccepted" to PushNotificationType.Other,
                "roleAssigned" to PushNotificationType.Other,
                "test" to PushNotificationType.Other,
                "login" to PushNotificationType.Other,
            )
        expected.forEach { (wireType, pushType) ->
            assertEquals(pushType, misskeyNotificationType(wireType), "type $wireType")
        }
    }

    @Test
    fun contentWarningReplacesNoteText() {
        val notification = MENTION_JSON.decodeJson<Notification>()
        val withCw = notification.copy(note = notification.note!!.copy(cw = "Hidden content"))

        val item = withCw.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("CW: Hidden content", item.text)
    }

    @Test
    fun longNoteTextIsTruncatedTo280PlusEllipsis() {
        val notification = MENTION_JSON.decodeJson<Notification>()
        val longText = "x".repeat(300)
        val withLongText = notification.copy(note = notification.note!!.copy(text = longText))

        val item = withLongText.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("x".repeat(280) + "…", item.text)
        assertEquals(281, item.text!!.length)
    }

    @Test
    fun localUserHandleUsesAccountHost() {
        val notification = FOLLOW_JSON.decodeJson<Notification>()
        val localUser = notification.user!!.copy(host = null)
        val withLocalUser = notification.copy(user = localUser)

        val item = withLocalUser.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("@newfollower@misskey.social", item.actorHandle)
    }

    @Test
    fun unparsableCreatedAtFallsBackToZero() {
        val notification = FOLLOW_JSON.decodeJson<Notification>()
        val withBadDate = notification.copy(createdAt = "not-a-date")

        val item = withBadDate.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(0L, item.createdAtEpochMillis)
    }
}
