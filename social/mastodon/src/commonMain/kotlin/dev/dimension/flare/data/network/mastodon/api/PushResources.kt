package dev.dimension.flare.data.network.mastodon.api

import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.Field
import de.jensklingenberg.ktorfit.http.FormUrlEncoded
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import dev.dimension.flare.data.network.mastodon.api.model.PushSubscription

internal interface PushResources {
    @GET("api/v1/push/subscription")
    suspend fun pushSubscription(): PushSubscription

    @POST("api/v1/push/subscription")
    @FormUrlEncoded
    suspend fun subscribePush(
        @Field("subscription[endpoint]") endpoint: String,
        @Field("subscription[keys][p256dh]") p256dh: String,
        @Field("subscription[keys][auth]") auth: String,
        @Field("subscription[standard]") standard: Boolean,
        @Field("data[policy]") policy: String,
        @Field("data[alerts][mention]") mentionAlert: Boolean,
        @Field("data[alerts][status]") statusAlert: Boolean,
        @Field("data[alerts][reblog]") reblogAlert: Boolean,
        @Field("data[alerts][follow]") followAlert: Boolean,
        @Field("data[alerts][follow_request]") followRequestAlert: Boolean,
        @Field("data[alerts][favourite]") favouriteAlert: Boolean,
        @Field("data[alerts][poll]") pollAlert: Boolean,
        @Field("data[alerts][update]") updateAlert: Boolean,
        @Field("data[alerts][quote]") quoteAlert: Boolean,
    ): PushSubscription

    @PUT("api/v1/push/subscription")
    @FormUrlEncoded
    suspend fun updatePushSubscription(
        @Field("data[policy]") policy: String,
        @Field("data[alerts][mention]") mentionAlert: Boolean,
        @Field("data[alerts][status]") statusAlert: Boolean,
        @Field("data[alerts][reblog]") reblogAlert: Boolean,
        @Field("data[alerts][follow]") followAlert: Boolean,
        @Field("data[alerts][follow_request]") followRequestAlert: Boolean,
        @Field("data[alerts][favourite]") favouriteAlert: Boolean,
        @Field("data[alerts][poll]") pollAlert: Boolean,
        @Field("data[alerts][update]") updateAlert: Boolean,
        @Field("data[alerts][quote]") quoteAlert: Boolean,
    ): PushSubscription

    @DELETE("api/v1/push/subscription")
    suspend fun deletePushSubscription()
}
