package dev.dimension.flare.push

import dev.dimension.flare.data.push.PushAccount
import dev.dimension.flare.data.push.PushAccountState
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationItem
import dev.dimension.flare.data.push.PushNotificationService
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake distributor standing in for the UnifiedPush connector. */
internal class FakeDistributorGateway(
    installed: List<String> = emptyList(),
    saved: String? = null,
    var failRegisterFor: MicroBlogKey? = null,
) : DistributorGateway {
    var installedDistributors: List<String> = installed
    var savedPackage: String? = saved
    val savedCalls = mutableListOf<String>()
    val registered = mutableListOf<Triple<String, String, String>>()
    val unregistered = mutableListOf<String>()

    override fun getDistributors(): List<String> = installedDistributors

    override fun getSavedDistributor(): String? = savedPackage

    override fun saveDistributor(packageName: String) {
        savedCalls.add(packageName)
        savedPackage = packageName
    }

    override fun register(
        instance: String,
        messageForDistributor: String,
        vapid: String,
    ) {
        failRegisterFor?.let { failure ->
            if (instance == failure.toString()) {
                throw IllegalStateException("vapid not valid for $instance")
            }
        }
        registered.add(Triple(instance, messageForDistributor, vapid))
    }

    override fun unregister(instance: String) {
        unregistered.add(instance)
    }
}

/** In-memory [PushNotificationService] recording what the registrar does to it. */
internal class FakePushNotificationService(
    accountList: List<PushAccount>,
    isEnabled: Boolean = true,
    toRegisterKeys: List<MicroBlogKey>? = null,
) : PushNotificationService {
    private val accountKeys = accountList.map { it.accountKey }
    override val enabled = MutableStateFlow(isEnabled)
    override val categories = MutableStateFlow(emptySet<PushCategory>())
    override val accounts = MutableStateFlow(accountList)
    override val removedAccounts: Flow<MicroBlogKey> = MutableStateFlow(accountKeys.firstOrNull() ?: MicroBlogKey("none", "none"))

    val setEnabledCalls = mutableListOf<Boolean>()
    val setAccountEnabledCalls = mutableListOf<Pair<MicroBlogKey, Boolean>>()
    val registrationFailures = mutableListOf<Pair<MicroBlogKey, String>>()
    val deactivated = mutableListOf<MicroBlogKey>()

    /** Accounts `accountsToRegister` returns; defaults to the enabled + supported accounts. */
    var explicitToRegister: List<MicroBlogKey>? = toRegisterKeys

    /** VAPID returned by `prepareRegistration`; null entries make it return null (ServerDisabled). */
    val vapidKeys = mutableMapOf<MicroBlogKey, String?>()

    override suspend fun setEnabled(enabled: Boolean) {
        setEnabledCalls.add(enabled)
        this.enabled.value = enabled
    }

    override suspend fun setAccountEnabled(
        accountKey: MicroBlogKey,
        enabled: Boolean,
    ) {
        setAccountEnabledCalls.add(accountKey to enabled)
    }

    override suspend fun setCategories(categories: Set<PushCategory>) = Unit

    override suspend fun accountsToRegister(): List<MicroBlogKey> =
        explicitToRegister
            ?: accounts.value.filter { it.supported && it.enabled }.map { it.accountKey }

    override suspend fun prepareRegistration(accountKey: MicroBlogKey): String? {
        // containsKey, not getOrPut: a stored null means ServerDisabled (return null).
        if (vapidKeys.containsKey(accountKey)) return vapidKeys[accountKey]
        val vapid = VAPID
        vapidKeys[accountKey] = vapid
        return vapid
    }

    override suspend fun onNewEndpoint(
        accountKey: MicroBlogKey,
        endpoint: String,
        p256dh: String,
        auth: String,
    ) = Unit

    override suspend fun onRegistrationFailed(
        accountKey: MicroBlogKey,
        reason: String,
    ) {
        registrationFailures.add(accountKey to reason)
    }

    override suspend fun onUnregistered(accountKey: MicroBlogKey) = Unit

    override suspend fun deactivate(accountKey: MicroBlogKey) {
        deactivated.add(accountKey)
    }

    override suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem> = emptyList()

    companion object {
        /** 87 chars, matching the connector's VAPID shape; the gateway is a fake so format is free. */
        val VAPID = "B" + "x".repeat(85) + "A"
    }
}

internal fun pushAccount(
    key: MicroBlogKey,
    supported: Boolean = true,
    enabled: Boolean = true,
): PushAccount =
    PushAccount(
        accountKey = key,
        platformId = if (supported) "mastodon" else "nostr",
        supported = supported,
        enabled = enabled,
        state = if (supported) PushAccountState.Off else PushAccountState.Unsupported,
        lastError = null,
    )

class PushRegistrarTest {
    private val distributor = PushRegistrar.NTFY_PACKAGE
    private val account1 = MicroBlogKey("alice", "example.com")
    private val account2 = MicroBlogKey("bob", "example.com")
    private val unsupported = MicroBlogKey("carol", "nostr")

    private fun registrar(
        service: FakePushNotificationService,
        gateway: FakeDistributorGateway,
    ): PushRegistrar = PushRegistrar(service, gateway)

    @Test
    fun enabledWithSavedDistributorRegistersEveryAccountWithVapidAndMessage() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2)),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).sync()
            assertEquals(
                listOf(
                    Triple(account1.toString(), PushRegistrar.MESSAGE_FOR_DISTRIBUTOR, FakePushNotificationService.VAPID),
                    Triple(account2.toString(), PushRegistrar.MESSAGE_FOR_DISTRIBUTOR, FakePushNotificationService.VAPID),
                ),
                gateway.registered,
            )
            assertTrue(gateway.unregistered.isEmpty())
            assertTrue(service.deactivated.isEmpty())
            assertTrue(gateway.savedCalls.isEmpty())
        }

    @Test
    fun noneSavedWithExactlyOneInstalledAutoSavesThenRegisters() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account1)))
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = null)
            registrar(service, gateway).sync()
            assertEquals(listOf(distributor), gateway.savedCalls)
            assertEquals(1, gateway.registered.size)
            assertEquals(account1.toString(), gateway.registered.single().first)
        }

    @Test
    fun noneSavedWithTwoInstalledRegistersNothingAndChangesNoState() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2)),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor, "app.alt.push"), saved = null)
            registrar(service, gateway).sync()
            assertTrue(gateway.registered.isEmpty())
            assertTrue(gateway.savedCalls.isEmpty())
            assertTrue(gateway.unregistered.isEmpty())
            assertTrue(service.deactivated.isEmpty())
            assertTrue(service.registrationFailures.isEmpty())
        }

    @Test
    fun savedDistributorUninstalledIsTreatedAsNone() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account1)))
            // The saved package no longer appears in the installed list.
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = "gone.app")
            registrar(service, gateway).sync()
            // Treated as none: the single installed distributor is auto-saved and used.
            assertEquals(listOf(distributor), gateway.savedCalls)
            assertEquals(1, gateway.registered.size)
        }

    @Test
    fun disabledUnregistersEveryKnownInstance() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2), pushAccount(unsupported, supported = false)),
                    isEnabled = false,
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).sync()
            assertEquals(
                listOf(account1.toString(), account2.toString(), unsupported.toString()),
                gateway.unregistered,
            )
            assertTrue(gateway.registered.isEmpty())
            assertTrue(service.deactivated.isEmpty())
        }

    @Test
    fun accountToggledOffIsUnregisteredAndDeactivated() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2, enabled = false)),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).sync()
            assertEquals(account1.toString(), gateway.registered.single().first)
            assertEquals(listOf(account2.toString()), gateway.unregistered)
            assertEquals(listOf(account2), service.deactivated)
        }

    @Test
    fun prepareRegistrationNullRegistersAndDeactivatesNothing() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account1)))
            service.vapidKeys[account1] = null
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).sync()
            assertTrue(gateway.registered.isEmpty())
            assertTrue(gateway.unregistered.isEmpty())
            assertTrue(service.deactivated.isEmpty())
        }

    @Test
    fun registerThrowingFailsOnlyThatAccountAndTheNextStillRegisters() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2)),
                )
            val gateway =
                FakeDistributorGateway(
                    installed = listOf(distributor),
                    saved = distributor,
                    failRegisterFor = account1,
                )
            registrar(service, gateway).sync()
            assertEquals(listOf(account1 to "vapid not valid for $account1"), service.registrationFailures)
            assertEquals(listOf(account2.toString()), gateway.registered.map { it.first })
        }

    @Test
    fun unsupportedAccountsAreNeverDeactivatedOrUnregisteredBySync() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(unsupported, supported = false)),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).sync()
            assertEquals(listOf(account1.toString()), gateway.registered.map { it.first })
            assertTrue(gateway.unregistered.isEmpty())
            assertTrue(service.deactivated.isEmpty())
        }

    @Test
    fun disableAllDeactivatesEveryAccountAndSetsDisabled() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account1), pushAccount(account2), pushAccount(unsupported, supported = false)),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            registrar(service, gateway).disableAll()
            assertEquals(
                listOf(account1.toString(), account2.toString(), unsupported.toString()),
                gateway.unregistered,
            )
            assertEquals(listOf(account1, account2, unsupported), service.deactivated)
            assertEquals(listOf(false), service.setEnabledCalls)
            assertTrue(gateway.registered.isEmpty())
        }
}
