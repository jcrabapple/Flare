package dev.dimension.flare.push

import android.content.Context
import dev.dimension.flare.data.push.PushNotificationService
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import org.unifiedpush.android.connector.UnifiedPush

@Single
internal class PushRegistrar(
    @Provided private val context: Context,
    private val service: PushNotificationService,
) {
    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** The saved distributor, or null when none is saved or it has been uninstalled since. */
    fun currentDistributor(): String? =
        UnifiedPush
            .getSavedDistributor(context)
            ?.takeIf { it.isNotBlank() && it in distributors() }

    fun selectDistributor(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
    }

    /**
     * D9: called on every app start. When push is enabled, (re-)register every account that should
     * be registered and unregister + deactivate the ones that should no longer be; when push is
     * disabled, unregister every known instance.
     */
    suspend fun sync() {
        val enabled = service.enabled.first()
        val known = service.accounts.first().map { it.accountKey }
        if (!enabled) {
            known.forEach { UnifiedPush.unregister(context, it.toString()) }
            return
        }
        // Without an installed, saved distributor UnifiedPush.register cannot succeed; leave the
        // account states alone until the user picks one in the settings screen.
        if (currentDistributor() == null) return
        val toRegister = service.accountsToRegister()
        toRegister.forEach { accountKey ->
            // null means the server has push off (the service marked the account ServerDisabled);
            // there is nothing to register for that account.
            val vapid = service.prepareRegistration(accountKey) ?: return@forEach
            UnifiedPush.register(context, accountKey.toString(), MESSAGE_FOR_DISTRIBUTOR, vapid)
        }
        (known.toSet() - toRegister.toSet()).forEach { accountKey ->
            UnifiedPush.unregister(context, accountKey.toString())
            service.deactivate(accountKey)
        }
    }

    suspend fun disableAll() {
        val known = service.accounts.first().map { it.accountKey }
        known.forEach {
            UnifiedPush.unregister(context, it.toString())
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
