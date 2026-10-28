package dev.dimension.flare.data.network.mastodon.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PushSubscription(
    val id: String? = null,
    val endpoint: String? = null,
    @SerialName("server_key")
    val serverKey: String? = null,
    val alerts: Map<String, Boolean>? = null,
)
