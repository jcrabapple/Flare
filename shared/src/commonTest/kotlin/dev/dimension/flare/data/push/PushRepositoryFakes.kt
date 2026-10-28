package dev.dimension.flare.data.push

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.model.tab.TimelineSpec
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.model.PlatformMetadata
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformRuntimeData
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred

/** A [MicroblogDataSource] that is not a [PushSubscriptionDataSource] (unsupported platform). */
internal open class FakePlainDataSource : MicroblogDataSource {
    override fun homeTimeline(): RemoteLoader<UiTimelineV2> = notSupported()

    override fun userTimeline(
        userKey: MicroBlogKey,
        mediaOnly: Boolean,
    ): RemoteLoader<UiTimelineV2> = notSupported()

    override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = notSupported()

    override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = notSupported()

    override fun searchUser(query: String): RemoteLoader<UiProfile> = notSupported()

    override fun discoverUsers(): RemoteLoader<UiProfile> = notSupported()

    override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = notSupported()

    override fun discoverHashtags(): RemoteLoader<UiHashtag> = notSupported()

    override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = notSupported()

    override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = notSupported()

    override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> = persistentListOf()
}

/**
 * In-memory [PushSubscriptionDataSource] fake. [notifications] is newest-first, like the real
 * endpoints; [fetchNotificationsSince] honours [sinceId] by list position (ids are opaque).
 */
internal class FakePushDataSource(
    private val delegate: MicroblogDataSource = FakePlainDataSource(),
) : MicroblogDataSource by delegate,
    PushSubscriptionDataSource {
    var serverKey: PushServerKey = PushServerKey.Available("test-vapid-key")

    var subscribeError: Exception? = null
    var serverKeyError: Exception? = null
    var updatePushCategoriesError: Exception? = null
    var fetchError: Exception? = null

    /** Newest-first. */
    var notifications: List<PushNotificationItem> = emptyList()

    var latestId: String? = null

    val subscribedEndpoints = mutableListOf<String>()
    val subscribedCategories = mutableListOf<Set<PushCategory>>()
    val updatePushCategoriesCalls = mutableListOf<Set<PushCategory>>()
    val unsubscribedEndpoints = mutableListOf<String>()
    val fetchCalls = mutableListOf<String?>()
    var latestNotificationIdCalls = 0

    /** Tracks overlapping fetchNotificationsSince calls; must never exceed 1 (mutex test). */
    var concurrentFetches = 0
    var maxConcurrentFetches = 0

    /** When set, the first fetchNotificationsSince call blocks until released. */
    var fetchGate: CompletableDeferred<Unit>? = null

    override suspend fun pushServerKey(): PushServerKey {
        serverKeyError?.let { throw it }
        return serverKey
    }

    override suspend fun subscribePush(
        endpoint: String,
        p256dh: String,
        auth: String,
        categories: Set<PushCategory>,
    ) {
        subscribeError?.let { throw it }
        subscribedEndpoints.add(endpoint)
        subscribedCategories.add(categories)
    }

    override suspend fun updatePushCategories(categories: Set<PushCategory>) {
        updatePushCategoriesError?.let { throw it }
        updatePushCategoriesCalls.add(categories)
    }

    override suspend fun unsubscribePush(endpoint: String) {
        unsubscribedEndpoints.add(endpoint)
    }

    override suspend fun latestNotificationId(): String? {
        latestNotificationIdCalls += 1
        return latestId
    }

    override suspend fun fetchNotificationsSince(
        sinceId: String?,
        limit: Int,
    ): List<PushNotificationItem> {
        concurrentFetches += 1
        if (concurrentFetches > maxConcurrentFetches) maxConcurrentFetches = concurrentFetches
        try {
            fetchGate?.await()
            fetchError?.let { throw it }
            fetchCalls.add(sinceId)
            // Newest-first list: items strictly newer than sinceId are the ones BEFORE it.
            val newer =
                if (sinceId == null) {
                    notifications
                } else {
                    val index = notifications.indexOfFirst { it.notificationId == sinceId }
                    if (index <= 0) return emptyList()
                    notifications.take(index)
                }
            return newer.take(limit)
        } finally {
            concurrentFetches -= 1
        }
    }
}

internal fun pushSpec(
    platformId: String,
    isDefaultGuest: Boolean = false,
    createDataSource: (PlatformDataSourceContext) -> MicroblogDataSource,
): PlatformSpec =
    object : PlatformSpec {
        override val platformId: String = platformId
        override val metadata: PlatformMetadata = PlatformMetadata(platformId, UiIcon.World)
        override val isDefaultGuest: Boolean = isDefaultGuest
        override val timelineSpecs: ImmutableList<TimelineSpec<out TimelineSpec.Data>> = persistentListOf()

        override fun deepLinks(accountKey: MicroBlogKey): ImmutableList<PlatformDeepLink<*>> = persistentListOf()

        override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource = createDataSource(context)

        override fun guestDataSource(
            host: String,
            locale: String,
        ): MicroblogDataSource = error("Guest data source is not available in push tests")
    }

internal fun testPushRegistry(
    pushDataSource: MicroblogDataSource,
    plainDataSource: MicroblogDataSource,
): PlatformRegistry =
    PlatformRegistry(
        PlatformRuntimeData(
            platformSpecs =
                listOf(
                    pushSpec(platformId = TEST_PUSH_PLATFORM, isDefaultGuest = true) { pushDataSource },
                    pushSpec(platformId = TEST_PLAIN_PLATFORM) { plainDataSource },
                ),
            extraTimelineSpecs = emptyList(),
        ),
    )

internal const val TEST_PUSH_PLATFORM = "TestPush"
internal const val TEST_PLAIN_PLATFORM = "TestPlain"

internal fun pushItem(
    notificationId: String,
    type: PushNotificationType = PushNotificationType.Mention,
    accountKey: MicroBlogKey = MicroBlogKey("alice", "test.social"),
): PushNotificationItem =
    PushNotificationItem(
        accountKey = accountKey,
        notificationId = notificationId,
        type = type,
        actorName = "Actor",
        actorHandle = "@actor@test.social",
        actorAvatarUrl = null,
        actorKey = MicroBlogKey("actor", "test.social"),
        statusKey = MicroBlogKey(notificationId, "test.social"),
        text = "hello",
        reaction = null,
        createdAtEpochMillis = 0L,
    )
