package dev.dimension.flare.data.network.mastodon.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class InstanceInfoV1(
    val uri: String? = null,
    val title: String? = null,
    @SerialName("short_description")
    val shortDescription: String? = null,
    val description: String? = null,
    val version: String? = null,
    val configuration: Configuration? = null,
    /** Pleroma/Akkoma extension object; their VAPID key lives here, not under `configuration`. */
    @SerialName("pleroma")
    val pleroma: PleromaInstanceInfo? = null,
    @SerialName("max_toot_chars")
    val maxTootChars: Long? = null,
)

/** Pleroma/Akkoma extra fields on `GET api/v1/instance`. Both VAPID key spellings exist in the wild. */
@Serializable
internal data class PleromaInstanceInfo(
    @SerialName("vapid_public_key")
    val vapidPublicKeySnake: String? = null,
    @SerialName("vapidPublicKey")
    val vapidPublicKeyCamel: String? = null,
)
