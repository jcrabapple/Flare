package dev.dimension.flare.data.datasource.mastodon

import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.data.network.mastodon.api.model.Notification
import dev.dimension.flare.data.network.mastodon.api.model.NotificationTypes
import dev.dimension.flare.data.network.mastodon.api.model.Status
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.category
import dev.dimension.flare.model.MicroBlogKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ACCOUNT_KEY = MicroBlogKey(id = "1", host = "mastodon.social")

private val MENTION_WITH_CW_JSON =
    """
    {
      "id": "109843190626423965",
      "type": "mention",
      "created_at": "2023-02-17T19:30:00.000Z",
      "account": {
        "id": "109803394899488999",
        "username": "testuser",
        "acct": "testuser@example.com",
        "display_name": "Test User",
        "locked": false,
        "bot": false,
        "url": "https://example.com/@testuser",
        "avatar": "https://files.example.com/avatar.png",
        "followers_count": 1,
        "following_count": 2,
        "statuses_count": 3
      },
      "status": {
        "id": "109843190626423984",
        "created_at": "2023-02-17T19:30:00.000Z",
        "in_reply_to_id": "109843190626423900",
        "in_reply_to_account_id": "109803394899488998",
        "sensitive": true,
        "spoiler_text": "Warning: spoilers",
        "visibility": "direct",
        "uri": "https://example.com/users/testuser/statuses/109843190626423984",
        "url": "https://example.com/@testuser/109843190626423984",
        "replies_count": 0,
        "reblogs_count": 0,
        "favourites_count": 0,
        "content": "<p>Hello from the docs!</p>",
        "account": {
          "id": "109803394899488999",
          "username": "testuser",
          "acct": "testuser@example.com"
        }
      }
    }
    """.trimIndent()

private val FOLLOW_JSON =
    """
    {
      "id": "109843191426423966",
      "type": "follow",
      "created_at": "2023-02-17T19:33:00.000Z",
      "account": {
        "id": "109813204413387999",
        "username": "another",
        "acct": "another@elsewhere.example",
        "display_name": "Another User",
        "url": "https://elsewhere.example/@another",
        "avatar": "https://elsewhere.example/avatar.png"
      }
    }
    """.trimIndent()

private val UNKNOWN_TYPE_JSON =
    """
    {
      "id": "109843192426423967",
      "type": "admin.report",
      "created_at": "2023-02-17T19:36:00.000Z",
      "account": {
        "id": "109813204413387999",
        "username": "moderator",
        "acct": "moderator"
      }
    }
    """.trimIndent()

class MastodonPushTest {
    @Test
    fun mentionWithHtmlContentAndSpoilerUsesCwText() {
        val notification = MENTION_WITH_CW_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("109843190626423965", item.notificationId)
        assertEquals(PushNotificationType.Mention, item.type)
        assertEquals("CW: Warning: spoilers", item.text)
        assertEquals("Test User", item.actorName)
        assertEquals("@testuser@example.com", item.actorHandle)
        assertEquals("https://files.example.com/avatar.png", item.actorAvatarUrl)
        assertEquals(
            MicroBlogKey(id = "109803394899488999", host = "mastodon.social"),
            item.actorKey,
        )
        assertEquals(
            MicroBlogKey(id = "109843190626423984", host = "mastodon.social"),
            item.statusKey,
        )
        assertEquals(ACCOUNT_KEY, item.accountKey)
        assertEquals(1676662200000L, item.createdAtEpochMillis)
        assertNull(item.reaction)
    }

    @Test
    fun mentionWithoutSpoilerUsesPlainTextOfHtmlContent() {
        val notification = MENTION_WITH_CW_JSON.decodeJson<Notification>()
        val status = notification.status!!.copy(spoilerText = null)

        val item = notification.copy(status = status).toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("Hello from the docs!", item.text)
    }

    @Test
    fun followNotificationWithoutStatusHasNoText() {
        val notification = FOLLOW_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Follow, item.type)
        assertNull(item.text)
        assertNull(item.statusKey)
        assertEquals("Another User", item.actorName)
        assertEquals("@another@elsewhere.example", item.actorHandle)
        assertEquals(
            MicroBlogKey(id = "109813204413387999", host = "mastodon.social"),
            item.actorKey,
        )
    }

    @Test
    fun localAcctGetsAccountHostAppended() {
        val notification = UNKNOWN_TYPE_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("@moderator@mastodon.social", item.actorHandle)
    }

    @Test
    fun unknownNotificationTypeMapsToOther() {
        val notification = UNKNOWN_TYPE_JSON.decodeJson<Notification>()

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Other, item.type)
        assertNull(item.statusKey)
    }

    @Test
    fun nullTypeMapsToOther() {
        val notification = Notification(id = "42", type = null)

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Other, item.type)
    }

    @Test
    fun notificationWithoutIdIsDropped() {
        val notification = Notification(id = null, type = null)

        assertNull(notification.toPushNotificationItem(ACCOUNT_KEY))
    }

    @Test
    fun longContentIsTruncatedTo280PlusEllipsis() {
        val status = Status(content = "<p>${"a".repeat(400)}</p>")
        val notification = Notification(id = "1", type = null, status = status)

        val text = notification.toPushNotificationItem(ACCOUNT_KEY)!!.text!!

        assertEquals(281, text.length)
        assertTrue(text.endsWith("…"))
        assertTrue(text.startsWith("a"))
        assertEquals("a".repeat(280) + "…", text)
    }

    @Test
    fun blankContentYieldsNullText() {
        val notification = Notification(id = "1", type = null, status = Status(content = "<p></p>"))

        assertNull(notification.toPushNotificationItem(ACCOUNT_KEY)!!.text)
    }

    @Test
    fun quoteNotificationMapsToQuoteWithStatus() {
        val notification = MENTION_WITH_CW_JSON.decodeJson<Notification>().copy(type = NotificationTypes.Quote)

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Quote, item.type)
        assertEquals(PushCategory.Reblog, item.type.category)
        assertEquals(
            MicroBlogKey(id = "109843190626423984", host = "mastodon.social"),
            item.statusKey,
        )
    }

    @Test
    fun quotedUpdateNotificationMapsToUpdateWithStatus() {
        val notification = MENTION_WITH_CW_JSON.decodeJson<Notification>().copy(type = NotificationTypes.QuotedUpdate)

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals(PushNotificationType.Update, item.type)
        assertEquals(PushCategory.Other, item.type.category)
        assertEquals(
            MicroBlogKey(id = "109843190626423984", host = "mastodon.social"),
            item.statusKey,
        )
    }

    @Test
    fun wireQuoteTypesParseThroughJson() {
        val notification = """{"id": "42", "type": "quote"}""".decodeJson<Notification>()
        val quotedUpdate = """{"id": "43", "type": "quoted_update"}""".decodeJson<Notification>()

        assertEquals(NotificationTypes.Quote, notification.type)
        assertEquals(NotificationTypes.QuotedUpdate, quotedUpdate.type)
    }

    @Test
    fun truncationDoesNotSplitSurrogatePairAtTheBoundary() {
        // The emoji's high surrogate lands exactly on index 279; the cut must back off to 279.
        val text = "a".repeat(279) + "😀"
        val status = Status(content = "<p>$text</p>")
        val notification = Notification(id = "1", type = null, status = status)

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("a".repeat(279) + "…", item.text)
        assertEquals(280, item.text!!.length)
    }

    @Test
    fun truncationKeepsACompleteSurrogatePairBeforeTheCut() {
        // The pair ends on index 279 (low surrogate last): take(280) already keeps it whole.
        val text = "a".repeat(278) + "😀" + "b"
        val status = Status(content = "<p>$text</p>")
        val notification = Notification(id = "1", type = null, status = status)

        val item = notification.toPushNotificationItem(ACCOUNT_KEY)!!

        assertEquals("a".repeat(278) + "😀" + "…", item.text)
    }

    @Test
    fun contentAtExactly280CharsIsNotTruncated() {
        val text = "a".repeat(280)
        val status = Status(content = "<p>$text</p>")
        val notification = Notification(id = "1", type = null, status = status)

        assertEquals(text, notification.toPushNotificationItem(ACCOUNT_KEY)!!.text)
    }

    @Test
    fun alertsForMentionAndReblog() {
        val alerts = alertsFor(setOf(PushCategory.Mention, PushCategory.Reblog))

        assertEquals(
            mapOf(
                "mention" to true,
                "status" to false,
                "reblog" to true,
                "follow" to false,
                "follow_request" to false,
                "favourite" to false,
                "poll" to false,
                "update" to false,
                "quote" to true,
            ),
            alerts,
        )
    }

    @Test
    fun alertsForEveryCategory() {
        val alerts = alertsFor(PushCategory.entries.toSet())

        assertEquals(9, alerts.size)
        assertTrue(alerts.values.all { it })
    }

    @Test
    fun alertsForEmptyCategories() {
        val alerts = alertsFor(emptySet())

        assertTrue(alerts.values.none { it })
    }

    @Test
    fun alertsPerCategoryTable() {
        assertEquals(
            setOf("mention"),
            alertsFor(setOf(PushCategory.Mention)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("follow"),
            alertsFor(setOf(PushCategory.Follow)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("follow_request"),
            alertsFor(setOf(PushCategory.FollowRequest)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("favourite"),
            alertsFor(setOf(PushCategory.Favourite)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("reblog", "quote"),
            alertsFor(setOf(PushCategory.Reblog)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("poll"),
            alertsFor(setOf(PushCategory.Poll)).filterValues { it }.keys,
        )
        assertEquals(
            setOf("status", "update"),
            alertsFor(setOf(PushCategory.Other)).filterValues { it }.keys,
        )
        assertFalse(alertsFor(setOf(PushCategory.Mention)).getValue("quote"))
    }
}
