package dev.dimension.flare.data.push

import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.PushAccountSettings
import dev.dimension.flare.data.push.PushAccountState.Active
import dev.dimension.flare.data.push.PushAccountState.Error
import dev.dimension.flare.data.push.PushAccountState.Off
import dev.dimension.flare.data.push.PushAccountState.Registering
import dev.dimension.flare.data.push.PushAccountState.ServerDisabled
import dev.dimension.flare.data.push.PushAccountState.Unsupported
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

// Push limit per wake-up, see DESIGN.md D4/D6.
private const val FETCH_LIMIT = 30

@Single(binds = [PushNotificationService::class])
internal class PushNotificationRepository(
    private val accountRepository: AccountRepository,
    private val appDataStore: AppDataStore,
) : PushNotificationService {
    private val settings get() = appDataStore.pushSettingsStore

    private val fetchMutexes = mutableMapOf<MicroBlogKey, Mutex>()
    private val fetchMutexesMutex = Mutex()

    override val enabled: Flow<Boolean> =
        settings
            .data
            .map { it.enabled }
            .distinctUntilChanged()

    override val categories: Flow<Set<PushCategory>> =
        settings
            .data
            .map { it.categories }
            .distinctUntilChanged()

    override val accounts: Flow<List<PushAccount>> =
        combine(
            accountRepository.allAccounts,
            settings.data,
        ) { allAccounts, pushSettings ->
            allAccounts.map { account ->
                val accountSettings = pushSettings.accounts[account.accountKey.toString()]
                val supported = isSupported(account)
                PushAccount(
                    accountKey = account.accountKey,
                    platformId = account.platformId,
                    supported = supported,
                    enabled = accountSettings?.enabled ?: true,
                    state = if (supported) accountSettings?.state ?: Off else Unsupported,
                    lastError = accountSettings?.lastError,
                )
            }
        }.distinctUntilChanged()

    override val removedAccounts: Flow<MicroBlogKey> get() = accountRepository.onRemoved

    override suspend fun setEnabled(enabled: Boolean) {
        // Only the global flag changes; per-account lastSeenId is kept so re-enabling resumes
        // instead of re-posting everything since the subscription (DESIGN.md D6).
        settings.updateData { it.copy(enabled = enabled) }
    }

    override suspend fun setAccountEnabled(
        accountKey: MicroBlogKey,
        enabled: Boolean,
    ) {
        updateAccountSettings(accountKey) { it.copy(enabled = enabled) }
    }

    override suspend fun setCategories(categories: Set<PushCategory>) {
        settings.updateData { it.copy(categories = categories) }
        val pushSettings = settings.data.first()
        pushSettings.accounts.forEach { (key, accountSettings) ->
            if (accountSettings.state != Active) return@forEach
            val accountKey = MicroBlogKey.valueOf(key)
            val dataSource = pushDataSourceOrNull(accountKey) ?: return@forEach
            try {
                dataSource.updatePushCategories(categories)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PushReauthRequiredException) {
                // The token lost the push scope (e.g. a PUT 403): the account is no longer usable
                // for push until the user signs in again. Still not rethrown (contract).
                updateAccountSettings(accountKey) {
                    it.copy(state = PushAccountState.ReauthRequired, lastError = error.message)
                }
            } catch (error: Exception) {
                error.printStackTrace()
            }
        }
    }

    override suspend fun accountsToRegister(): List<MicroBlogKey> {
        val pushSettings = settings.data.first()
        if (!pushSettings.enabled) return emptyList()
        return accountRepository.allAccounts
            .first()
            .mapNotNull { account ->
                val accountSettings = pushSettings.accounts[account.accountKey.toString()]
                val enabled = accountSettings?.enabled ?: true
                if (enabled && isSupported(account)) account.accountKey else null
            }
    }

    override suspend fun prepareRegistration(accountKey: MicroBlogKey): String? {
        // A removed account must not leave an orphan settings entry behind.
        if (accountRepository.find(accountKey) == null) return null
        updateAccountSettings(accountKey) { it.copy(state = Registering, lastError = null) }
        val dataSource =
            pushDataSourceOrNull(accountKey) ?: run {
                updateAccountSettings(accountKey) { it.copy(state = Unsupported, lastError = null) }
                return null
            }
        return try {
            when (val serverKey = dataSource.pushServerKey()) {
                is PushServerKey.Available -> {
                    serverKey.vapidPublicKey
                }

                is PushServerKey.Disabled -> {
                    updateAccountSettings(accountKey) { it.copy(state = ServerDisabled) }
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateAccountSettings(accountKey) {
                it.copy(state = Error, lastError = error.message ?: error::class.simpleName)
            }
            null
        }
    }

    override suspend fun onNewEndpoint(
        accountKey: MicroBlogKey,
        endpoint: String,
        p256dh: String,
        auth: String,
    ) {
        // Same per-account mutex as fetchNew: the settings read-modify-write below (subscribe →
        // baseline → activate) must not interleave with an in-flight fetch. Network calls inside
        // the lock are fine; nothing here calls another locked method.
        mutexFor(accountKey).withLock {
            val dataSource = pushDataSourceOrNull(accountKey)
            if (dataSource == null) {
                updateAccountSettings(accountKey) {
                    it.copy(state = Error, lastError = NOT_SUPPORTED_MESSAGE)
                }
                return@withLock
            }
            val categories = settings.data.first().categories
            try {
                dataSource.subscribePush(endpoint, p256dh, auth, categories)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PushReauthRequiredException) {
                updateAccountSettings(accountKey) {
                    it.copy(state = PushAccountState.ReauthRequired, lastError = error.message)
                }
                return@withLock
            } catch (error: Exception) {
                updateAccountSettings(accountKey) {
                    it.copy(state = Error, lastError = error.message ?: error::class.simpleName)
                }
                return@withLock
            }
            // D6: baseline whenever this is a fresh subscription (no stored endpoint: first subscribe, or
            // re-activation after deactivate/onUnregistered) so enabling push never floods notifications
            // that piled up while it was off. A re-subscribe for an endpoint refresh keeps lastSeenId so
            // nothing that arrived during the refresh is skipped.
            val current = settings.data.first().accounts[accountKey.toString()]
            val freshSubscription = current?.endpoint == null || current.lastSeenId == null
            val baseline =
                if (freshSubscription) {
                    try {
                        dataSource.latestNotificationId()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        error.printStackTrace()
                        null
                    }
                } else {
                    null
                }
            updateAccountSettings(accountKey) {
                it.copy(
                    state = Active,
                    endpoint = endpoint,
                    lastError = null,
                    lastSeenId = if (freshSubscription) baseline ?: it.lastSeenId else it.lastSeenId,
                )
            }
        }
    }

    override suspend fun onRegistrationFailed(
        accountKey: MicroBlogKey,
        reason: String,
    ) {
        updateAccountSettings(accountKey) { it.copy(state = Error, lastError = reason) }
    }

    override suspend fun onUnregistered(accountKey: MicroBlogKey) {
        mutexFor(accountKey).withLock {
            updateAccountSettings(accountKey) { it.copy(state = Off, endpoint = null, lastError = null) }
        }
    }

    override suspend fun deactivate(accountKey: MicroBlogKey) {
        mutexFor(accountKey).withLock {
            var storedEndpoint: String? = null
            updateAccountSettings(accountKey) {
                storedEndpoint = it.endpoint
                it.copy(state = Off, endpoint = null, lastError = null)
            }
            val endpoint = storedEndpoint ?: return@withLock
            val dataSource = pushDataSourceOrNull(accountKey) ?: return@withLock
            try {
                dataSource.unsubscribePush(endpoint)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort: the server drops the subscription once the credential is gone anyway.
                error.printStackTrace()
            }
        }
    }

    override suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem> =
        // One mutex per account: concurrent wake-ups for the same account are serialized so a
        // second fetch sees the advanced lastSeenId and cannot double-post (DESIGN.md D12). The
        // same mutex guards the re-registration/unsubscribe settings transitions in onNewEndpoint,
        // deactivate and onUnregistered; nothing under the lock calls another locked method.
        mutexFor(accountKey).withLock {
            val pushSettings = settings.data.first()
            if (!pushSettings.enabled) return emptyList()
            val accountSettings = pushSettings.accounts[accountKey.toString()]
            if (accountSettings?.enabled == false) return emptyList()
            // A dead subscription must not fetch or advance lastSeenId: Off, ServerDisabled,
            // ReauthRequired and Unsupported are terminal until a re-registration. Registering and
            // Error are transient re-registration states, and an existing subscription must keep
            // working meanwhile; a missing entry keeps the previous (fetch) behaviour.
            val storedState = accountSettings?.state
            if (storedState != null && storedState !in FETCHABLE_STATES) return emptyList()
            val dataSource = pushDataSourceOrNull(accountKey) ?: return emptyList()
            val items =
                try {
                    dataSource.fetchNotificationsSince(accountSettings?.lastSeenId, FETCH_LIMIT)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PushReauthRequiredException) {
                    // A revoked credential is permanent: record it so the UI can ask for a
                    // re-login, then let the worker see the failure (no retry).
                    updateAccountSettings(accountKey) {
                        it.copy(state = PushAccountState.ReauthRequired, lastError = error.message)
                    }
                    throw error
                }
            if (items.isEmpty()) return emptyList()
            // Ids are opaque strings (Mastodon numeric strings, GtS ULIDs, Misskey aid) — the list
            // is newest-first, so the newest id is the first item by position, never by comparison.
            val newestId = items.first().notificationId
            if (newestId != accountSettings?.lastSeenId) {
                updateAccountSettings(accountKey) { it.copy(lastSeenId = newestId) }
            }
            items
                .asReversed()
                .filter { it.type.category in pushSettings.categories }
        }

    private suspend fun mutexFor(accountKey: MicroBlogKey): Mutex =
        // fetchMutexes is only mutated under fetchMutexesMutex.
        fetchMutexesMutex.withLock { fetchMutexes.getOrPut(accountKey) { Mutex() } }

    private suspend fun updateAccountSettings(
        accountKey: MicroBlogKey,
        transform: (PushAccountSettings) -> PushAccountSettings,
    ) {
        settings.updateData { pushSettings ->
            val key = accountKey.toString()
            val current = pushSettings.accounts[key] ?: PushAccountSettings()
            pushSettings.copy(accounts = pushSettings.accounts + (key to transform(current)))
        }
    }

    private suspend fun pushDataSourceOrNull(accountKey: MicroBlogKey): PushSubscriptionDataSource? {
        val account = accountRepository.find(accountKey) ?: return null
        return try {
            accountRepository.getOrCreateDataSource(account) as? PushSubscriptionDataSource
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private suspend fun isSupported(account: UiAccount): Boolean =
        try {
            accountRepository.getOrCreateDataSource(account) is PushSubscriptionDataSource
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }

    private companion object {
        private const val NOT_SUPPORTED_MESSAGE = "Push notifications are not supported on this platform"

        /** Account states an existing subscription may fetch from; anything else must not. */
        private val FETCHABLE_STATES = setOf(Active, Registering, Error)
    }
}
