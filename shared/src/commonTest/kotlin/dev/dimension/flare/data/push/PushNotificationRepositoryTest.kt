package dev.dimension.flare.data.push

import androidx.room3.Room
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.app.model.DbAccount
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.PushAccountSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushNotificationRepositoryTest : RobolectricTest() {
    private val root = createTestRootPath()
    private val fileStorage = OkioFileStorage(createTestFileSystem(), root)
    private val pushDataSource = FakePushDataSource()
    private val plainDataSource = FakePlainDataSource()

    private lateinit var appDatabase: AppDatabase
    private lateinit var cacheDatabase: CacheDatabase
    private lateinit var appDataStore: AppDataStore
    private lateinit var accountRepository: AccountRepository

    private val accountKey = MicroBlogKey(id = "alice", host = "test.social")
    private val plainAccountKey = MicroBlogKey(id = "bob", host = "test.social")

    @BeforeTest
    fun setup() {
        appDatabase =
            Room
                .memoryDatabaseBuilder<AppDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
        cacheDatabase =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
        appDataStore = AppDataStore(fileStorage)
    }

    @AfterTest
    fun tearDown() {
        appDatabase.close()
        cacheDatabase.close()
        deleteTestRootPath(root)
    }

    @Test
    fun accountsFlowCombinesAccountsSettingsAndSupport() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            insertAccount(plainAccountKey, TEST_PLAIN_PLATFORM)
            val service = createService(this)

            val accounts = service.accounts.first()
            assertEquals(2, accounts.size)
            val push = accounts.single { it.accountKey == accountKey }
            val plain = accounts.single { it.accountKey == plainAccountKey }
            assertTrue(push.supported)
            assertEquals(TEST_PUSH_PLATFORM, push.platformId)
            assertEquals(PushAccountState.Off, push.state)
            assertTrue(push.enabled)
            assertNull(push.lastError)
            assertFalse(plain.supported)
            assertEquals(PushAccountState.Unsupported, plain.state)
        }

    @Test
    fun prepareRegistrationReturnsVapidKeyAndMarksRegistering() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)

            assertEquals("test-vapid-key", service.prepareRegistration(accountKey))
            assertEquals(PushAccountState.Registering, accountSettings(accountKey)?.state)
        }

    @Test
    fun prepareRegistrationMarksServerDisabledWhenServerHasPushOff() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.serverKey = PushServerKey.Disabled
            val service = createService(this)

            assertNull(service.prepareRegistration(accountKey))
            assertEquals(PushServerKey.Disabled, pushDataSource.serverKey)
            assertEquals(PushAccountState.ServerDisabled, accountSettings(accountKey)?.state)
        }

    @Test
    fun prepareRegistrationMarksErrorWhenServerKeyFails() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.serverKeyError = IllegalStateException("no meta")
            val service = createService(this)

            assertNull(service.prepareRegistration(accountKey))
            assertEquals(PushAccountState.Error, accountSettings(accountKey)?.state)
            assertEquals("no meta", accountSettings(accountKey)?.lastError)
        }

    @Test
    fun onNewEndpointSubscribesActivatesAndBaselinesLastSeenId() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)

            service.prepareRegistration(accountKey)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            assertEquals(listOf(ENDPOINT), pushDataSource.subscribedEndpoints)
            assertEquals(listOf(PushCategory.entries.toSet()), pushDataSource.subscribedCategories)
            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Active, settings?.state)
            assertEquals(ENDPOINT, settings?.endpoint)
            assertEquals("n100", settings?.lastSeenId)
            assertNull(settings?.lastError)
            assertEquals(1, pushDataSource.latestNotificationIdCalls)
        }

    @Test
    fun onNewEndpointDoesNotOverwriteExistingBaselineOnResubscribe() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            pushDataSource.latestId = "n999"
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            assertEquals(2, pushDataSource.subscribedEndpoints.size)
            assertEquals("n100", accountSettings(accountKey)?.lastSeenId)
            assertEquals(1, pushDataSource.latestNotificationIdCalls)
        }

    @Test
    fun onNewEndpointMarksReauthRequired() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.subscribeError = PushReauthRequiredException("token lacks push scope")
            val service = createService(this)

            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.ReauthRequired, settings?.state)
            assertEquals("token lacks push scope", settings?.lastError)
        }

    @Test
    fun onNewEndpointMarksErrorOnOtherSubscribeFailures() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.subscribeError = IllegalStateException("boom")
            val service = createService(this)

            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Error, settings?.state)
            assertEquals("boom", settings?.lastError)
        }

    @Test
    fun fetchNewReturnsOldestFirstFilteredAndAdvancesPastFilteredItems() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            service.setCategories(setOf(PushCategory.Mention, PushCategory.Poll))
            pushDataSource.notifications =
                listOf(
                    pushItem("n3", PushNotificationType.Mention),
                    pushItem("n2", PushNotificationType.Reblog),
                    pushItem("n1", PushNotificationType.Mention),
                )

            val items = service.fetchNew(accountKey)

            // Oldest-first, Reblog filtered out...
            assertEquals(listOf(pushItem("n1"), pushItem("n3")), items)
            // ...but lastSeenId advanced past the filtered-out item (position-based, newest first).
            assertEquals("n3", accountSettings(accountKey)?.lastSeenId)
            assertEquals(listOf<String?>(null), pushDataSource.fetchCalls)
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenPushIsDisabled() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenAccountIsDisabled() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            service.setAccountEnabled(accountKey, false)

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun setEnabledFalseKeepsLastSeenIdSoReEnableResumes() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Active)
            pushDataSource.notifications = listOf(pushItem("n1"))
            assertTrue(service.fetchNew(accountKey).isNotEmpty())
            assertEquals("n1", accountSettings(accountKey)?.lastSeenId)

            service.setEnabled(false)
            assertEquals("n1", accountSettings(accountKey)?.lastSeenId)

            // Newer notifications arrive while push is off; re-enabling fetches only the newer ones.
            service.setEnabled(true)
            pushDataSource.notifications =
                listOf(
                    pushItem("n3"),
                    pushItem("n2"),
                    pushItem("n1"),
                )
            assertEquals(listOf(pushItem("n2"), pushItem("n3")), service.fetchNew(accountKey))
            assertEquals("n3", accountSettings(accountKey)?.lastSeenId)
        }

    @Test
    fun setCategoriesPersistsAndUpdatesOnlyActiveAccounts() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            insertAccount(plainAccountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")
            // A second push-capable account with a settings entry that is NOT Active.
            service.onRegistrationFailed(plainAccountKey, "distributor gone")

            val categories = setOf(PushCategory.Mention, PushCategory.Poll)
            service.setCategories(categories)

            assertEquals(
                categories,
                appDataStore
                    .pushSettingsStore
                    .data
                    .first()
                    .categories,
            )
            assertEquals(listOf(categories), pushDataSource.updatePushCategoriesCalls)
        }

    @Test
    fun accountsToRegisterFiltersByEnabledSupportAndAccountToggle() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            insertAccount(plainAccountKey, TEST_PLAIN_PLATFORM)
            val service = createService(this)

            assertTrue(service.accountsToRegister().isEmpty())

            service.setEnabled(true)
            assertEquals(listOf(accountKey), service.accountsToRegister())

            service.setAccountEnabled(accountKey, false)
            assertTrue(service.accountsToRegister().isEmpty())
        }

    @Test
    fun concurrentFetchNewPostsEachItemOnce() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Active)
            pushDataSource.notifications =
                listOf(
                    pushItem("n2"),
                    pushItem("n1"),
                )
            val gate = CompletableDeferred<Unit>()
            pushDataSource.fetchGate = gate

            val first = launch { service.fetchNew(accountKey) }
            runCurrent()
            val second = launch { service.fetchNew(accountKey) }
            runCurrent()
            assertTrue(second.isActive, "Second fetch must block on the per-account mutex")
            gate.complete(Unit)
            first.join()
            second.join()

            assertEquals(listOf<String?>(null, "n2"), pushDataSource.fetchCalls)
            assertEquals(1, pushDataSource.maxConcurrentFetches)
            assertEquals("n2", accountSettings(accountKey)?.lastSeenId)
        }

    @Test
    fun deactivateWithoutStoredStateWritesNothing() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)

            service.deactivate(accountKey)

            assertNull(accountSettings(accountKey))
            assertTrue(pushDataSource.unsubscribedEndpoints.isEmpty())
        }

    @Test
    fun deactivateMarksOffAndUnsubscribesWithStoredEndpoint() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            service.deactivate(accountKey)

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Off, settings?.state)
            assertNull(settings?.endpoint)
            assertEquals("n100", settings?.lastSeenId)
            assertEquals(listOf(ENDPOINT), pushDataSource.unsubscribedEndpoints)
        }

    @Test
    fun onNewEndpointRebaselinesWhenReactivatedAfterDeactivate() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")
            service.deactivate(accountKey)

            // Notifications piled up while push was off; re-enabling must not post them.
            pushDataSource.latestId = "n500"
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            assertEquals("n500", accountSettings(accountKey)?.lastSeenId)
            assertEquals(PushAccountState.Active, accountSettings(accountKey)?.state)
        }

    @Test
    fun onNewEndpointRebaselinesAfterDistributorUnregistered() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")
            service.onUnregistered(accountKey)

            pushDataSource.latestId = "n200"
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")

            assertEquals("n200", accountSettings(accountKey)?.lastSeenId)
        }

    @Test
    fun onRegistrationFailedMarksErrorAndOnUnregisteredMarksOff() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)

            service.onRegistrationFailed(accountKey, "distributor gone")
            assertEquals(PushAccountState.Error, accountSettings(accountKey)?.state)
            assertEquals("distributor gone", accountSettings(accountKey)?.lastError)

            service.onUnregistered(accountKey)
            assertEquals(PushAccountState.Off, accountSettings(accountKey)?.state)
        }

    @Test
    fun removedAccountsReExposesAccountRemoval() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            val removals = Channel<MicroBlogKey>(Channel.UNLIMITED)
            val observer =
                launch {
                    service.removedAccounts.collect { removals.send(it) }
                }

            accountRepository.delete(accountKey)

            assertEquals(accountKey, removals.receive())
            observer.cancel()
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenStateIsOff() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Off)
            pushDataSource.notifications = listOf(pushItem("n1"))

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenStateIsServerDisabled() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.ServerDisabled)
            pushDataSource.notifications = listOf(pushItem("n1"))

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenStateIsReauthRequired() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.ReauthRequired)
            pushDataSource.notifications = listOf(pushItem("n1"))

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun fetchNewDoesNoNetworkCallWhenStateIsUnsupported() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Unsupported)
            pushDataSource.notifications = listOf(pushItem("n1"))

            assertTrue(service.fetchNew(accountKey).isEmpty())
            assertTrue(pushDataSource.fetchCalls.isEmpty())
        }

    @Test
    fun fetchNewStillFetchesWhenStateIsRegistering() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Registering)
            pushDataSource.notifications = listOf(pushItem("n1"))

            assertEquals(listOf(pushItem("n1")), service.fetchNew(accountKey))
            assertEquals(listOf<String?>(null), pushDataSource.fetchCalls)
            assertEquals("n1", accountSettings(accountKey)?.lastSeenId)
        }

    @Test
    fun fetchNewMarksReauthRequiredAndRethrows() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            seedAccountState(accountKey, PushAccountState.Active)
            pushDataSource.fetchError = PushReauthRequiredException("token revoked")

            assertFailsWith<PushReauthRequiredException> {
                service.fetchNew(accountKey)
            }

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.ReauthRequired, settings?.state)
            assertEquals("token revoked", settings?.lastError)
        }

    @Test
    fun setCategoriesMarksReauthRequiredWhenUpdateThrowsReauth() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")
            pushDataSource.updatePushCategoriesError = PushReauthRequiredException("token lacks push scope")

            service.setCategories(setOf(PushCategory.Mention))

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.ReauthRequired, settings?.state)
            assertEquals("token lacks push scope", settings?.lastError)
        }

    @Test
    fun setCategoriesKeepsStateOnOtherUpdateErrors() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key")
            pushDataSource.updatePushCategoriesError = IllegalStateException("boom")

            service.setCategories(setOf(PushCategory.Mention))

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Active, settings?.state)
        }

    @Test
    fun onNewEndpointWaitsForInFlightFetchAndBaselinesLastSeenId() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            service.setEnabled(true)
            pushDataSource.latestId = "n100"
            pushDataSource.notifications = listOf(pushItem("n1"))
            val gate = CompletableDeferred<Unit>()
            pushDataSource.fetchGate = gate

            val fetch = launch { service.fetchNew(accountKey) }
            runCurrent()
            val register = launch { service.onNewEndpoint(accountKey, ENDPOINT, "p256dh-key", "auth-key") }
            runCurrent()
            assertTrue(register.isActive, "onNewEndpoint must wait for the in-flight fetch")
            gate.complete(Unit)
            fetch.join()
            register.join()

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Active, settings?.state)
            assertEquals(ENDPOINT, settings?.endpoint)
            // The baseline from onNewEndpoint wins over the lastSeenId the earlier fetch advanced.
            assertEquals("n100", settings?.lastSeenId)
        }

    @Test
    fun prepareRegistrationStoresUnsupportedForUnsupportedPlatform() =
        runTest {
            insertAccount(plainAccountKey, TEST_PLAIN_PLATFORM)
            val service = createService(this)

            assertNull(service.prepareRegistration(plainAccountKey))
            val settings = accountSettings(plainAccountKey)
            assertEquals(PushAccountState.Unsupported, settings?.state)
            assertNull(settings?.lastError)
        }

    @Test
    fun prepareRegistrationWritesNothingForRemovedAccount() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            val service = createService(this)
            accountRepository.delete(accountKey)

            assertNull(service.prepareRegistration(accountKey))
            assertNull(accountSettings(accountKey))
        }

    @Test
    fun deactivateClearsLastError() =
        runTest {
            insertAccount(accountKey, TEST_PUSH_PLATFORM)
            pushDataSource.latestId = "n100"
            val service = createService(this)
            service.onRegistrationFailed(accountKey, "distributor gone")
            assertEquals("distributor gone", accountSettings(accountKey)?.lastError)

            service.deactivate(accountKey)

            val settings = accountSettings(accountKey)
            assertEquals(PushAccountState.Off, settings?.state)
            assertNull(settings?.lastError)
        }

    private suspend fun seedAccountState(
        accountKey: MicroBlogKey,
        state: PushAccountState,
    ) {
        appDataStore.pushSettingsStore.updateData { pushSettings ->
            val key = accountKey.toString()
            val current = pushSettings.accounts[key] ?: PushAccountSettings()
            pushSettings.copy(accounts = pushSettings.accounts + (key to current.copy(state = state)))
        }
    }

    private suspend fun accountSettings(accountKey: MicroBlogKey) =
        appDataStore
            .pushSettingsStore
            .data
            .first()
            .accounts[accountKey.toString()]

    private fun createService(scope: CoroutineScope): PushNotificationRepository {
        val registry = testPushRegistry(pushDataSource, plainDataSource)
        accountRepository =
            AccountRepository(
                appDatabase = appDatabase,
                coroutineScope = scope,
                appDataStore = appDataStore,
                cacheDatabase = cacheDatabase,
                proxyRepository = ProxyRepository(fileStorage),
                platformRegistry = registry,
            )
        return PushNotificationRepository(accountRepository, appDataStore)
    }

    private suspend fun insertAccount(
        key: MicroBlogKey,
        platformId: String,
    ) {
        appDatabase.accountDao().insert(
            DbAccount(
                account_key = key,
                credential_json = "{}",
                platformId = platformId,
                last_active = 1L,
                sort_id = 0L,
            ),
        )
    }

    private companion object {
        private const val ENDPOINT = "https://push.example/endpoint"
    }
}
