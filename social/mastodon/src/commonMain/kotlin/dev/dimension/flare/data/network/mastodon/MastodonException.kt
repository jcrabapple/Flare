package dev.dimension.flare.data.network.mastodon

import dev.dimension.flare.common.decodeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class MastodonException(
    @SerialName("error")
    val error: String? = null,
    /** HTTP status of the response that carried this error body; set by the response validator. */
    @Transient
    val statusCode: Int? = null,
) : Exception(error)

internal fun String.toMastodonExceptionOrNull(): MastodonException? =
    runCatching {
        decodeJson<MastodonException>()
    }.getOrNull()
        ?.takeIf { !it.error.isNullOrBlank() }
