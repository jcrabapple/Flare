package dev.dimension.flare.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.dimension.flare.MainActivity
import dev.dimension.flare.R
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PushNotifierTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val accountKey = MicroBlogKey("me", "example.com")
    private lateinit var notifier: PushNotifier
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notifier = PushNotifier(context)
        notifier.ensureChannels()
    }

    private fun item(
        type: PushNotificationType,
        accountKey: MicroBlogKey = this.accountKey,
        notificationId: String = "n1",
        actorName: String? = "Alice",
        actorHandle: String? = "@alice@example.com",
        actorAvatarUrl: String? = null,
        actorKey: MicroBlogKey? = MicroBlogKey("alice", "example.com"),
        statusKey: MicroBlogKey? = null,
        text: String? = null,
        reaction: String? = null,
        createdAtEpochMillis: Long = 1_700_000_000_000L,
    ): PushNotificationItem =
        PushNotificationItem(
            accountKey = accountKey,
            notificationId = notificationId,
            type = type,
            actorName = actorName,
            actorHandle = actorHandle,
            actorAvatarUrl = actorAvatarUrl,
            actorKey = actorKey,
            statusKey = statusKey,
            text = text,
            reaction = reaction,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun posted(): List<Notification> = shadowOf(notificationManager).allNotifications

    /** [PushNotifier.show] is suspend; tests call this blocking wrapper. */
    private fun showBlocking(items: List<PushNotificationItem>) = kotlinx.coroutines.runBlocking { notifier.show(items) }

    private fun firstExtras(): android.os.Bundle = posted().first().extras

    @Test
    fun createsTheSixChannels() {
        // Channels already created in setUp; verify against a fresh manager too.
        val fresh = PushNotifier(context)
        fresh.ensureChannels()
        val ids = notificationManager.notificationChannels.map { it.id }
        assertEquals(
            setOf(
                PushNotifier.CHANNEL_MENTION,
                PushNotifier.CHANNEL_FOLLOW,
                PushNotifier.CHANNEL_FAVOURITE,
                PushNotifier.CHANNEL_REBLOG,
                PushNotifier.CHANNEL_POLL,
                PushNotifier.CHANNEL_OTHER,
            ),
            ids.toSet(),
        )
        assertEquals(6, ids.size)
        assertEquals(
            "Mentions",
            notificationManager.notificationChannels.first { it.id == PushNotifier.CHANNEL_MENTION }.name,
        )
    }

    @Test
    fun postsMentionTitle() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertEquals("Alice mentioned you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun postsFollowTitle() {
        showBlocking(listOf(item(PushNotificationType.Follow)))
        assertEquals("Alice followed you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun postsReactionTitleWithEmoji() {
        showBlocking(listOf(item(PushNotificationType.Reaction, reaction = "\uD83C\uDF89")))
        assertEquals(
            "Alice reacted \uD83C\uDF89 to your post",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun postsPollTitleWithoutActor() {
        showBlocking(listOf(item(PushNotificationType.Poll, actorName = null, actorHandle = null)))
        assertEquals(
            "A poll you voted in or created has ended",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun fallsBackToHandleThenSomeoneForTheActor() {
        showBlocking(listOf(item(PushNotificationType.Mention, actorName = null)))
        assertEquals(
            "@alice@example.com mentioned you",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
        notificationManager.cancelAll()
        showBlocking(
            listOf(item(PushNotificationType.Mention, actorName = null, actorHandle = null)),
        )
        assertEquals("Someone mentioned you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun usesBigTextWhenTextIsPresent() {
        showBlocking(listOf(item(PushNotificationType.Mention, text = "hello there")))
        val extras = firstExtras()
        assertEquals("hello there", extras.getString(NotificationCompat.EXTRA_BIG_TEXT))
    }

    @Test
    fun omitsBigTextWhenTextIsAbsent() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertNull(firstExtras().getString(NotificationCompat.EXTRA_BIG_TEXT))
    }

    @Test
    fun showsSubTextForTheAccountHost() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertEquals("example.com", firstExtras().getString(NotificationCompat.EXTRA_SUB_TEXT))
    }

    @Test
    fun setsWhenForKnownTimeOnly() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertEquals(1_700_000_000_000L, posted().first().`when`)
        notificationManager.cancelAll()
        // 0 means unknown: the notification keeps the system default timestamp instead of 1970.
        val before = System.currentTimeMillis()
        showBlocking(listOf(item(PushNotificationType.Mention, createdAtEpochMillis = 0L)))
        val whenValue = posted().first().`when`
        assertTrue(whenValue in before..System.currentTimeMillis())
    }

    @Test
    fun groupsPerAccountAndAddsSummaryOnlyAtTwo() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertEquals(1, posted().size)
        assertEquals("push_$accountKey", posted().first().group)

        notificationManager.cancelAll()
        showBlocking(
            listOf(
                item(PushNotificationType.Mention, notificationId = "n1"),
                item(PushNotificationType.Follow, notificationId = "n2"),
            ),
        )
        assertEquals(3, posted().size)
        assertTrue(posted().all { it.group == "push_$accountKey" })
        // Exactly one extra notification (the summary), titled with the grouped count.
        val summaries = posted().filter { it.extras.getString(NotificationCompat.EXTRA_TITLE) == "2 new notifications" }
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().group == "push_$accountKey")

        notificationManager.cancelAll()
        val otherAccount = MicroBlogKey("other", "elsewhere.social")
        showBlocking(
            listOf(
                item(PushNotificationType.Mention, notificationId = "n1"),
                item(PushNotificationType.Mention, notificationId = "n2", accountKey = otherAccount),
            ),
        )
        assertEquals(2, posted().size)
        assertTrue(posted().none { it.extras.getString(NotificationCompat.EXTRA_TITLE) == "2 new notifications" })
    }

    @Test
    fun statusItemOpensStatusDetail() {
        val statusKey = MicroBlogKey("status123", "example.com")
        showBlocking(listOf(item(PushNotificationType.Mention, statusKey = statusKey)))
        val intent = shadowOf(posted().first().contentIntent).savedIntent
        val expected =
            DeeplinkRoute
                .Status
                .Detail(statusKey, AccountType.Specific(accountKey))
                .toUri()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(expected), intent.data)
    }

    @Test
    fun followItemOpensUserProfile() {
        val actorKey = MicroBlogKey("alice", "example.com")
        showBlocking(listOf(item(PushNotificationType.Follow, actorKey = actorKey)))
        val intent = shadowOf(posted().first().contentIntent).savedIntent
        val expected =
            DeeplinkRoute
                .Profile
                .User(AccountType.Specific(accountKey), actorKey)
                .toUri()
        assertEquals(Uri.parse(expected), intent.data)
    }

    @Test
    fun itemWithoutKeysUsesPlainLaunchIntent() {
        showBlocking(
            listOf(item(PushNotificationType.Mention, actorKey = null, statusKey = null)),
        )
        val pendingIntent = posted().first().contentIntent
        val intent = shadowOf(pendingIntent).savedIntent
        assertNull(intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun cancelAccountRemovesOnlyThatAccountsNotifications() {
        val otherAccount = MicroBlogKey("other", "elsewhere.social")
        showBlocking(
            listOf(
                item(PushNotificationType.Mention, notificationId = "n1"),
                item(PushNotificationType.Follow, notificationId = "n2"),
                item(PushNotificationType.Mention, notificationId = "n3", accountKey = otherAccount),
            ),
        )
        assertEquals(4, posted().size)
        notifier.cancelAccount(accountKey)
        assertEquals(1, posted().size)
        assertTrue(posted().single().group == "push_$otherAccount")
    }

    @Test
    fun postsNothingWhenNotificationsAreDisabled() {
        shadowOf(notificationManager).setNotificationsEnabled(false)
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun postsUseTheDedicatedNotificationIcon() {
        showBlocking(listOf(item(PushNotificationType.Mention)))
        assertTrue(posted().isNotEmpty())
        posted().forEach { notification ->
            assertEquals(
                R.drawable.ic_notification,
                shadowOf(notification.smallIcon).resId,
            )
        }
    }

    @Test
    fun channelsMatchTheCategoryTableForEveryType() {
        val expected =
            mapOf(
                PushNotificationType.Mention to PushNotifier.CHANNEL_MENTION,
                PushNotificationType.Reply to PushNotifier.CHANNEL_MENTION,
                PushNotificationType.Follow to PushNotifier.CHANNEL_FOLLOW,
                PushNotificationType.FollowRequest to PushNotifier.CHANNEL_FOLLOW,
                PushNotificationType.Favourite to PushNotifier.CHANNEL_FAVOURITE,
                PushNotificationType.Reaction to PushNotifier.CHANNEL_FAVOURITE,
                PushNotificationType.Reblog to PushNotifier.CHANNEL_REBLOG,
                PushNotificationType.Quote to PushNotifier.CHANNEL_REBLOG,
                PushNotificationType.Poll to PushNotifier.CHANNEL_POLL,
                PushNotificationType.Status to PushNotifier.CHANNEL_OTHER,
                PushNotificationType.Update to PushNotifier.CHANNEL_OTHER,
                PushNotificationType.Other to PushNotifier.CHANNEL_OTHER,
            )
        PushNotificationType.entries.forEach { type ->
            notificationManager.cancelAll()
            showBlocking(listOf(item(type)))
            assertEquals("wrong channel for $type", 1, posted().size)
            assertEquals(
                "wrong channel for $type",
                expected.getValue(type),
                posted().single().channelId,
            )
        }
    }

    @Test
    fun titlesMatchTheCopyTableForEveryType() {
        val actor = "Alice"
        val expectedTitle: (PushNotificationType) -> String = { type ->
            when (type) {
                PushNotificationType.Mention -> context.getString(R.string.push_notif_mention, actor)
                PushNotificationType.Reply -> context.getString(R.string.push_notif_reply, actor)
                PushNotificationType.Follow -> context.getString(R.string.push_notif_follow, actor)
                PushNotificationType.FollowRequest -> context.getString(R.string.push_notif_follow_request, actor)
                PushNotificationType.Favourite -> context.getString(R.string.push_notif_favourite, actor)
                PushNotificationType.Reaction -> context.getString(R.string.push_notif_favourite, actor)
                PushNotificationType.Reblog -> context.getString(R.string.push_notif_reblog, actor)
                PushNotificationType.Quote -> context.getString(R.string.push_notif_quote, actor)
                PushNotificationType.Poll -> context.getString(R.string.push_notif_poll)
                PushNotificationType.Status -> context.getString(R.string.push_notif_status, actor)
                PushNotificationType.Update -> context.getString(R.string.push_notif_update, actor)
                PushNotificationType.Other -> context.getString(R.string.push_notif_other, actor)
            }
        }
        PushNotificationType.entries.forEach { type ->
            notificationManager.cancelAll()
            showBlocking(listOf(item(type, reaction = null)))
            assertEquals(
                "wrong title for $type",
                expectedTitle(type),
                firstExtras().getString(NotificationCompat.EXTRA_TITLE),
            )
        }
    }

    @Test
    fun reactionTitleUsesTheReactionStringWhenPresent() {
        showBlocking(listOf(item(PushNotificationType.Reaction, reaction = "\uD83C\uDF89")))
        assertEquals(
            context.getString(R.string.push_notif_reaction, "Alice", "\uD83C\uDF89"),
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun summaryReflectsWhatIsShowingAcrossWakeUps() {
        // First wake-up: a single item, below the summary threshold.
        showBlocking(listOf(item(PushNotificationType.Mention, notificationId = "n1")))
        assertTrue(
            posted().none { it.extras.getString(NotificationCompat.EXTRA_TITLE) == "1 new notifications" },
        )
        // Second wake-up: one more item; the summary appears and counts what is showing.
        showBlocking(listOf(item(PushNotificationType.Follow, notificationId = "n2")))
        val summaries2 =
            posted().filter { it.extras.getString(NotificationCompat.EXTRA_TITLE) == "2 new notifications" }
        assertEquals(1, summaries2.size)
        // Third wake-up: the summary is refreshed in place with the new total.
        showBlocking(listOf(item(PushNotificationType.Reblog, notificationId = "n3")))
        val summaries3 =
            posted().filter { it.extras.getString(NotificationCompat.EXTRA_TITLE)?.endsWith(" new notifications") == true }
        assertEquals(1, summaries3.size)
        assertEquals("3 new notifications", summaries3.single().extras.getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun loadsAvatarsForTheBatchConcurrently() {
        val avatarUrl = "https://example.com/avatar.png"
        val delayMillis = 200L
        var inFlight = 0
        var maxInFlight = 0
        notifier.avatarLoader = { url ->
            assertEquals(avatarUrl, url)
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(delayMillis)
            inFlight--
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }
        val items =
            (1..4).map { index ->
                item(PushNotificationType.Mention, notificationId = "n$index", actorAvatarUrl = avatarUrl)
            }
        val elapsed =
            kotlinx.coroutines.runBlocking {
                kotlin.system.measureTimeMillis { notifier.show(items) }
            }
        // All four loaders overlap (serial loading would never exceed 1 in flight), and the
        // whole batch costs near one delay, not 4 x delay.
        assertEquals(4, maxInFlight)
        assertTrue("avatar loading took ${elapsed}ms; expected near one delay (serial = ${4 * delayMillis}ms)", elapsed < 4 * delayMillis)
    }
}
