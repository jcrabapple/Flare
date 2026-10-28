package dev.dimension.flare.data.datastore.model

import dev.dimension.flare.data.push.PushAccountState
import dev.dimension.flare.data.push.PushCategory
import kotlinx.serialization.Serializable

/** Device-local push notification state (push_settings.pb), excluded from exported settings. */
@Serializable
internal data class PushSettings(
    val enabled: Boolean = false,
    val categories: Set<PushCategory> = PushCategory.entries.toSet(),
    /** Key = MicroBlogKey.toString(). */
    val accounts: Map<String, PushAccountSettings> = emptyMap(),
)

@Serializable
internal data class PushAccountSettings(
    val enabled: Boolean = true,
    val state: PushAccountState = PushAccountState.Off,
    val endpoint: String? = null,
    val lastSeenId: String? = null,
    val lastError: String? = null,
)
