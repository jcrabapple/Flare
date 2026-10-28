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
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertEquals("Alice mentioned you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun postsFollowTitle() {
        notifier.show(listOf(item(PushNotificationType.Follow)))
        assertEquals("Alice followed you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun postsReactionTitleWithEmoji() {
        notifier.show(listOf(item(PushNotificationType.Reaction, reaction = "\uD83C\uDF89")))
        assertEquals(
            "Alice reacted \uD83C\uDF89 to your post",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun postsPollTitleWithoutActor() {
        notifier.show(listOf(item(PushNotificationType.Poll, actorName = null, actorHandle = null)))
        assertEquals(
            "A poll you voted in or created has ended",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun fallsBackToHandleThenSomeoneForTheActor() {
        notifier.show(listOf(item(PushNotificationType.Mention, actorName = null)))
        assertEquals(
            "@alice@example.com mentioned you",
            firstExtras().getString(NotificationCompat.EXTRA_TITLE),
        )
        notificationManager.cancelAll()
        notifier.show(
            listOf(item(PushNotificationType.Mention, actorName = null, actorHandle = null)),
        )
        assertEquals("Someone mentioned you", firstExtras().getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun usesBigTextWhenTextIsPresent() {
        notifier.show(listOf(item(PushNotificationType.Mention, text = "hello there")))
        val extras = firstExtras()
        assertEquals("hello there", extras.getString(NotificationCompat.EXTRA_BIG_TEXT))
    }

    @Test
    fun omitsBigTextWhenTextIsAbsent() {
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertNull(firstExtras().getString(NotificationCompat.EXTRA_BIG_TEXT))
    }

    @Test
    fun showsSubTextForTheAccountHost() {
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertEquals("example.com", firstExtras().getString(NotificationCompat.EXTRA_SUB_TEXT))
    }

    @Test
    fun setsWhenForKnownTimeOnly() {
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertEquals(1_700_000_000_000L, posted().first().`when`)
        notificationManager.cancelAll()
        // 0 means unknown: the notification keeps the system default timestamp instead of 1970.
        val before = System.currentTimeMillis()
        notifier.show(listOf(item(PushNotificationType.Mention, createdAtEpochMillis = 0L)))
        val whenValue = posted().first().`when`
        assertTrue(whenValue in before..System.currentTimeMillis())
    }

    @Test
    fun groupsPerAccountAndAddsSummaryOnlyAtTwo() {
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertEquals(1, posted().size)
        assertEquals("push_$accountKey", posted().first().group)

        notificationManager.cancelAll()
        notifier.show(
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
        notifier.show(
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
        notifier.show(listOf(item(PushNotificationType.Mention, statusKey = statusKey)))
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
        notifier.show(listOf(item(PushNotificationType.Follow, actorKey = actorKey)))
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
        notifier.show(
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
        notifier.show(
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
        notifier.show(listOf(item(PushNotificationType.Mention)))
        assertTrue(posted().isEmpty())
    }
}
