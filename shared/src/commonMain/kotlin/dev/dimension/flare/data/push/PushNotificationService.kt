package dev.dimension.flare.data.push

import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

@Serializable
public enum class PushAccountState {
    Off,
    Registering,
    Active,
    ServerDisabled,
    ReauthRequired,
    Error,
    Unsupported,
}

public data class PushAccount(
    val accountKey: MicroBlogKey,
    val platformId: String,
    /** The account's data source implements [PushSubscriptionDataSource]. */
    val supported: Boolean,
    /** Per-account toggle, default true. */
    val enabled: Boolean,
    val state: PushAccountState,
    val lastError: String?,
)

/** Push notification state and logic shared by the Android runtime and settings UI. */
@HiddenFromObjC
public interface PushNotificationService {
    public val enabled: Flow<Boolean>
    public val categories: Flow<Set<PushCategory>>

    /** Every logged-in account, in AccountService order. */
    public val accounts: Flow<List<PushAccount>>
    public val removedAccounts: Flow<MicroBlogKey>

    public suspend fun setEnabled(enabled: Boolean)

    public suspend fun setAccountEnabled(
        accountKey: MicroBlogKey,
        enabled: Boolean,
    )

    /** Persists, then calls updatePushCategories on Active accounts (errors logged, not thrown). */
    public suspend fun setCategories(categories: Set<PushCategory>)

    /** Accounts to register now: push enabled && account enabled && supported. */
    public suspend fun accountsToRegister(): List<MicroBlogKey>

    /** Marks Registering and returns the VAPID key; marks ServerDisabled and returns null when push is off server-side. */
    public suspend fun prepareRegistration(accountKey: MicroBlogKey): String?

    public suspend fun onNewEndpoint(
        accountKey: MicroBlogKey,
        endpoint: String,
        p256dh: String,
        auth: String,
    )

    public suspend fun onRegistrationFailed(
        accountKey: MicroBlogKey,
        reason: String,
    )

    public suspend fun onUnregistered(accountKey: MicroBlogKey)

    /** Marks Off and best-effort unsubscribes on the server using the stored endpoint. */
    public suspend fun deactivate(accountKey: MicroBlogKey)

    /**
     * New items oldest-first, filtered by categories; advances lastSeenId to the newest fetched id
     * (including filtered-out items). Empty when push or the account is disabled.
     */
    public suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem>
}
