package dev.dimension.flare.push

import dev.dimension.flare.data.push.PushNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

internal class FlarePushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pushNotificationService: PushNotificationService by lazy {
        GlobalContext.get().get<PushNotificationService>()
    }

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        val accountKey = parseInstance(instance)
        if (accountKey == null) {
            unregister(instance)
            return
        }
        val pubKeySet = endpoint.pubKeySet
        scope.launch {
            if (pubKeySet == null) {
                pushNotificationService.onRegistrationFailed(accountKey, MISSING_KEYS_REASON)
            } else {
                pushNotificationService.onNewEndpoint(
                    accountKey = accountKey,
                    endpoint = endpoint.url,
                    p256dh = pubKeySet.pubKey,
                    auth = pubKeySet.auth,
                )
            }
        }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        // D4: the decrypted payload is ignored; the message only wakes a fetch for the account.
        val accountKey = parseInstance(instance)
        if (accountKey == null) {
            unregister(instance)
            return
        }
        PushFetchWorker.enqueue(applicationContext, accountKey)
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        val accountKey = parseInstance(instance)
        if (accountKey == null) {
            unregister(instance)
            return
        }
        scope.launch {
            pushNotificationService.onRegistrationFailed(accountKey, reason.name)
        }
    }

    override fun onUnregistered(instance: String) {
        val accountKey = parseInstance(instance)
        if (accountKey == null) {
            unregister(instance)
            return
        }
        scope.launch {
            pushNotificationService.onUnregistered(accountKey)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun unregister(instance: String) {
        UnifiedPush.unregister(applicationContext, instance)
    }

    private companion object {
        private const val MISSING_KEYS_REASON = "Distributor did not provide encryption keys"
    }
}
