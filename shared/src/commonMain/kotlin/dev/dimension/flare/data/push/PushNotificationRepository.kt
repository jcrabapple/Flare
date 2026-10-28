package dev.dimension.flare.data.push

import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single

// Wave-0 stub. Lane L3 replaces every body; the class name, constructor shape and binding stay.
@Single(binds = [PushNotificationService::class])
internal class PushNotificationRepository(
    private val accountRepository: AccountRepository,
    private val appDataStore: AppDataStore,
) : PushNotificationService {
    override val enabled: Flow<Boolean> get() = TODO("L3")
    override val categories: Flow<Set<PushCategory>> get() = TODO("L3")
    override val accounts: Flow<List<PushAccount>> get() = TODO("L3")
    override val removedAccounts: Flow<MicroBlogKey> get() = TODO("L3")

    override suspend fun setEnabled(enabled: Boolean): Unit = TODO("L3")

    override suspend fun setAccountEnabled(
        accountKey: MicroBlogKey,
        enabled: Boolean,
    ): Unit = TODO("L3")

    override suspend fun setCategories(categories: Set<PushCategory>): Unit = TODO("L3")

    override suspend fun accountsToRegister(): List<MicroBlogKey> = TODO("L3")

    override suspend fun prepareRegistration(accountKey: MicroBlogKey): String? = TODO("L3")

    override suspend fun onNewEndpoint(
        accountKey: MicroBlogKey,
        endpoint: String,
        p256dh: String,
        auth: String,
    ): Unit = TODO("L3")

    override suspend fun onRegistrationFailed(
        accountKey: MicroBlogKey,
        reason: String,
    ): Unit = TODO("L3")

    override suspend fun onUnregistered(accountKey: MicroBlogKey): Unit = TODO("L3")

    override suspend fun deactivate(accountKey: MicroBlogKey): Unit = TODO("L3")

    override suspend fun fetchNew(accountKey: MicroBlogKey): List<PushNotificationItem> = TODO("L3")
}
