package dev.dimension.flare.data.network.misskey.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class MisskeyException(
    val error: Error? = null,
    /** HTTP status of the response that carried this error body; set by the response validator. */
    @Transient
    val statusCode: Int? = null,
) : Exception(error?.message ?: "Unknown error") {
    @Serializable
    internal data class Error(
        val message: String? = null,
        val code: String? = null,
        val id: String? = null,
        val kind: String? = null,
    )
}
