package dev.dimension.flare.push

import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.screen.settings.NotificationSettingsActions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tests for [NotificationSettingsActions]: every toggle reconciles the registration via the
 * registrar, not just the stored setting. Uses a real [PushRegistrar] against the fakes, so "sync
 * happened" is observed through the fake gateway's calls.
 */
class NotificationSettingsActionsTest {
    private val distributor = PushRegistrar.NTFY_PACKAGE
    private val account = MicroBlogKey("alice", "example.com")

    @Test
    fun enablingSetsEnabledThenSyncs() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account)))
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            NotificationSettingsActions(service, PushRegistrar(service, gateway)).setEnabled(true)
            assertEquals(listOf(true), service.setEnabledCalls)
            assertEquals(listOf(account.toString()), gateway.registered.map { it.first })
        }

    @Test
    fun disablingCallsDisableAll() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account)))
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            NotificationSettingsActions(service, PushRegistrar(service, gateway)).setEnabled(false)
            // disableAll: unregister, deactivate, and flip the stored switch off.
            assertEquals(listOf(account.toString()), gateway.unregistered)
            assertEquals(listOf(account), service.deactivated)
            assertEquals(listOf(false), service.setEnabledCalls)
            assertTrue(gateway.registered.isEmpty())
        }

    @Test
    fun accountToggleCallsSetAccountEnabledThenSyncs() =
        runTest {
            val service =
                FakePushNotificationService(
                    listOf(pushAccount(account)),
                    toRegisterKeys = listOf(account),
                )
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = distributor)
            NotificationSettingsActions(service, PushRegistrar(service, gateway)).setAccountEnabled(account, true)
            assertEquals(listOf(account to true), service.setAccountEnabledCalls)
            assertEquals(listOf(account.toString()), gateway.registered.map { it.first })
        }

    @Test
    fun selectDistributorSavesThenSyncs() =
        runTest {
            val service = FakePushNotificationService(listOf(pushAccount(account)))
            val gateway = FakeDistributorGateway(installed = listOf(distributor), saved = null)
            NotificationSettingsActions(service, PushRegistrar(service, gateway)).selectDistributor(distributor)
            assertEquals(listOf(distributor), gateway.savedCalls)
            assertEquals(listOf(account.toString()), gateway.registered.map { it.first })
        }
}
