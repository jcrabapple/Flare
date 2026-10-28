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
            val dataSource = pushDataSourceOrNull(MicroBlogKey.valueOf(key)) ?: return@forEach
            try {
                dataSource.updatePushCategories(categories)
            } catch (error: CancellationException) {
                throw error
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
        updateAccountSettings(accountKey) { it.copy(state = Registering, lastError = null) }
        val dataSource =
            pushDataSourceOrNull(accountKey) ?: run {
                updateAccountSettings(accountKey) {
                    it.copy(state = Error, lastError = NOT_SUPPORTED_MESSAGE)
                }
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
        val dataSource = pushDataSourceOrNull(accountKey)
        if (dataSource == null) {
            updateAccountSettings(accountKey) {
                it.copy(state = Error, lastError = NOT_SUPPORTED_MESSAGE)
            }
            return
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
            return
        } catch (error: Exception) {
            updateAccountSettings(accountKey) {
                it.copy(state = Error, lastError = error.message ?: error::class.simpleName)
            }
            return
        }
        // D6: baseline on the first successful subscribe so enabling push never floods old
        // notifications. A re-subscribe (idempotent re-POST) must not overwrite the baseline.
        val currentLastSeenId =
            settings
                .data
                .first()
                .accounts[accountKey.toString()]
                ?.lastSeenId
        val baseline =
            if (currentLastSeenId == null) {
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
                lastSeenId = it.lastSeenId ?: baseline,
            )
        }
    }

    override suspend fun onRegistrationFailed(
        accountKey: MicroBlogKey,
        reason: String,
    ) {
        updateAccountSettings(accountKey) { it.copy(state = Error, lastError = reason) }
    }

    override suspend fun onUnregistered(accountKey: MicroBlogKey) {
        updateAccountSettings(accountKey) { it.copy(state = Off, endpoint = null, lastError = null) }
    }

    override suspend fun deactivate(accountKey: MicroBlogKey) {
        var storedEndpoint: String? = null
        updateAccountSettings(accountKey) {
            storedEndpoint = it.endpoint
            it.copy(state = Off, endpoint = null)
        }
        val endpoint = storedEndpoint ?: return
        val dataSource = pushDataSourceOrNull(accountKey) ?: return
        try {
            dataSource.unsubscribePush(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort: the server drops the subscription once the credential is gone anyway.
            error.printStackTrace()
        }
    }

    override suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem> =
        // One mutex per account: concurrent wake-ups for the same account are serialized so a
        // second fetch sees the advanced lastSeenId and cannot double-post (DESIGN.md D12).
        mutexFor(accountKey).withLock {
            val pushSettings = settings.data.first()
            if (!pushSettings.enabled) return emptyList()
            val accountSettings = pushSettings.accounts[accountKey.toString()]
            if (accountSettings?.enabled == false) return emptyList()
            val dataSource = pushDataSourceOrNull(accountKey) ?: return emptyList()
            val items = dataSource.fetchNotificationsSince(accountSettings?.lastSeenId, FETCH_LIMIT)
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
    }
}
