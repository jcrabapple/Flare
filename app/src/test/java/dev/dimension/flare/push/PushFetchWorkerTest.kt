package dev.dimension.flare.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.dimension.flare.data.push.PushAccount
import dev.dimension.flare.data.push.PushAccountState
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationService
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PushFetchWorkerTest {
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

    private class FakePushNotificationService(
        private val fetch: () -> List<PushNotificationItem>,
    ) : PushNotificationService {
        var fetchCalls = 0

        override val enabled = MutableStateFlow(false)
        override val categories = MutableStateFlow(emptySet<PushCategory>())
        override val accounts = MutableStateFlow(emptyList<PushAccount>())
        override val removedAccounts: Flow<MicroBlogKey> = emptyFlow()

        override suspend fun setEnabled(enabled: Boolean) = Unit

        override suspend fun setAccountEnabled(
            accountKey: MicroBlogKey,
            enabled: Boolean,
        ) = Unit

        override suspend fun setCategories(categories: Set<PushCategory>) = Unit

        override suspend fun accountsToRegister(): List<MicroBlogKey> = emptyList()

        override suspend fun prepareRegistration(accountKey: MicroBlogKey): String? = null

        override suspend fun onNewEndpoint(
            accountKey: MicroBlogKey,
            endpoint: String,
            p256dh: String,
            auth: String,
        ) = Unit

        override suspend fun onRegistrationFailed(
            accountKey: MicroBlogKey,
            reason: String,
        ) = Unit

        override suspend fun onUnregistered(accountKey: MicroBlogKey) = Unit

        override suspend fun deactivate(accountKey: MicroBlogKey) = Unit

        override suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem> {
            fetchCalls++
            return fetch()
        }
    }

    private fun item(notificationId: String = "n1"): PushNotificationItem =
        PushNotificationItem(
            accountKey = accountKey,
            notificationId = notificationId,
            type = PushNotificationType.Mention,
            actorName = "Alice",
            actorHandle = "@alice@example.com",
            actorAvatarUrl = null,
            actorKey = MicroBlogKey("alice", "example.com"),
            statusKey = MicroBlogKey("status1", "example.com"),
            text = "hello",
            reaction = null,
            createdAtEpochMillis = 1_700_000_000_000L,
        )

    private fun input(accountKey: MicroBlogKey? = this.accountKey): Data? =
        accountKey?.let { workDataOf(PushFetchWorker.KEY_ACCOUNT_KEY to it.toString()) }

    private fun runWorker(
        service: PushNotificationService,
        runAttemptCount: Int = 0,
        inputData: Data? = input(),
    ): ListenableWorker.Result =
        runBlocking {
            val builder =
                TestListenableWorkerBuilder<PushFetchWorker>(context)
                    .setRunAttemptCount(runAttemptCount)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = PushFetchWorker(appContext, workerParameters, service, notifier)
                        },
                    )
            if (inputData != null) builder.setInputData(inputData)
            builder.build().doWork()
        }

    private fun postedCount(): Int = shadowOf(notificationManager).allNotifications.size

    @Test
    fun fetchesAndPostsNotifications() {
        val service = FakePushNotificationService { listOf(item("n1"), item("n2")) }
        val result = runBlocking { runWorker(service) }
        assertEquals(1, service.fetchCalls)
        assertTrue(result is ListenableWorker.Result.Success)
        // Two item notifications plus the per-account summary at >= 2 items.
        assertEquals(3, postedCount())
    }

    @Test
    fun emptyFetchPostsNothing() {
        val service = FakePushNotificationService { emptyList() }
        val result = runBlocking { runWorker(service) }
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedCount())
    }

    @Test
    fun reauthFailsWithoutRetry() {
        val service = FakePushNotificationService { throw PushReauthRequiredException("revoked") }
        val result = runBlocking { runWorker(service) }
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(1, service.fetchCalls)
    }

    @Test
    fun transientErrorRetriesBelowMaxAttempts() {
        val service = FakePushNotificationService { throw IOException("offline") }
        // runAttemptCount 1 = the second attempt; one more is still allowed.
        val result = runBlocking { runWorker(service, runAttemptCount = 1) }
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun transientErrorFailsAtMaxAttempts() {
        val service = FakePushNotificationService { throw IOException("offline") }
        // runAttemptCount 2 = the third and last attempt (D12: max 3 attempts).
        val result = runBlocking { runWorker(service, runAttemptCount = PushFetchWorker.MAX_ATTEMPTS - 1) }
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun defaultWorkerFactoryCanInstantiateTheWorker() {
        // Production uses WorkManager's default reflective factory, which needs a
        // (Context, WorkerParameters) constructor. No custom factory here on purpose.
        val worker = TestListenableWorkerBuilder<PushFetchWorker>(context).build()
        assertTrue(worker is PushFetchWorker)
    }

    @Test
    fun missingAccountKeyFailsWithoutFetching() {
        val service = FakePushNotificationService { listOf(item()) }
        val result = runBlocking { runWorker(service, inputData = null) }
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, service.fetchCalls)
    }

    @Test
    fun unparseableAccountKeyFailsWithoutFetching() {
        val service = FakePushNotificationService { listOf(item()) }
        val result = runBlocking { runWorker(service, inputData = input(MicroBlogKey("", ""))) }
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, service.fetchCalls)
    }
}
