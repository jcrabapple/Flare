package dev.dimension.flare.push

import dev.dimension.flare.data.push.PushNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

@Single
internal class PushRegistrar(
    private val service: PushNotificationService,
    private val gateway: DistributorGateway,
) {
    fun distributors(): List<String> = gateway.getDistributors()

    /** The saved distributor, or null when none is saved or it has been uninstalled since. */
    fun currentDistributor(): String? =
        gateway
            .getSavedDistributor()
            ?.takeIf { it.isNotBlank() && it in distributors() }

    fun selectDistributor(packageName: String) {
        gateway.saveDistributor(packageName)
    }

    /** The saved distributor; if none is saved and exactly one is installed, saves and returns it. */
    fun ensureDistributor(): String? = currentDistributor() ?: distributors().singleOrNull()?.also { selectDistributor(it) }

    /**
     * D9: called on every app start. When push is enabled, (re-)register every account that should
     * be registered and unregister + deactivate the ones that should no longer be; when push is
     * disabled, unregister every known instance.
     */
    suspend fun sync() {
        val enabled = service.enabled.first()
        val known = service.accounts.first().map { it.accountKey }
        if (!enabled) {
            known.forEach { gateway.unregister(it.toString()) }
            return
        }
        // UnifiedPush.register needs a saved, installed distributor. With exactly one installed
        // (the common "just installed ntfy" case) save it automatically; with several, leave the
        // account states alone until the user picks one in the settings screen.
        if (ensureDistributor() == null) return
        val toRegister = service.accountsToRegister()
        toRegister.forEach { accountKey ->
            try {
                // null means the server has push off (the service marked the account
                // ServerDisabled); there is nothing to register for that account.
                val vapid = service.prepareRegistration(accountKey) ?: return@forEach
                gateway.register(accountKey.toString(), MESSAGE_FOR_DISTRIBUTOR, vapid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. VapidNotValidException for a malformed server key: fail this account only.
                service.onRegistrationFailed(accountKey, e.message ?: e::class.simpleName.orEmpty())
            }
        }
        // Only accounts push could have registered need cleanup; unsupported platforms never were.
        val supported =
            service.accounts
                .first()
                .filter { it.supported }
                .map { it.accountKey }
                .toSet()
        (supported - toRegister.toSet()).forEach { accountKey ->
            try {
                gateway.unregister(accountKey.toString())
                service.deactivate(accountKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun disableAll() {
        val known = service.accounts.first().map { it.accountKey }
        known.forEach {
            gateway.unregister(it.toString())
            // Best-effort server-side unsubscribe; marks the account Off.
            service.deactivate(it)
        }
        service.setEnabled(false)
    }

    companion object {
        const val NTFY_PACKAGE = "io.heckel.ntfy"

        /** D8: short description shown to the distributor for our registrations. */
        const val MESSAGE_FOR_DISTRIBUTOR = "Flare"
    }
}
