package dev.dimension.flare.data.datasource.mastodon

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.data.network.mastodon.api.InstanceResources
import dev.dimension.flare.data.network.mastodon.api.PushResources
import dev.dimension.flare.data.network.mastodon.api.TimelineResources
import dev.dimension.flare.data.network.mastodon.api.createPushResources
import dev.dimension.flare.data.network.mastodon.api.createTimelineResources
import dev.dimension.flare.data.network.mastodon.api.model.Configuration
import dev.dimension.flare.data.network.mastodon.api.model.InstanceData
import dev.dimension.flare.data.network.mastodon.api.model.InstanceInfoV1
import dev.dimension.flare.data.network.mastodon.api.model.MastodonPagingConverterFactory
import dev.dimension.flare.data.network.mastodon.api.model.PleromaInstanceInfo
import dev.dimension.flare.data.network.mastodon.api.model.Vapid
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.push.PushServerKey
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val ACCOUNT_KEY = MicroBlogKey(id = "1", host = "akkoma.example")

// Live Akkoma shape (pleroma.envs.net, 2026-10-28): /api/v2/instance answers 404 and the key lives
// at pleroma.vapid_public_key on v1, where `configuration` is present but carries no vapid key.
private val AKKOMA_V1_JSON =
    """
    {
      "uri": "https://akkoma.example",
      "version": "3.20.1",
      "configuration": {},
      "pleroma": {
        "vapid_public_key": "BIUU7Ywr2HQlAKKOMA_VAPID_KEY"
      }
    }
    """.trimIndent()

private val V2_INSTANCE_WITH_KEY =
    InstanceData(
        domain = "mastodon.example",
        configuration = Configuration(vapid = Vapid(publicKey = "V2_VAPID_KEY")),
    )

private val V2_INSTANCE_WITHOUT_KEY = InstanceData(domain = "mastodon.example")

/** Push/timeline APIs backed by a MockEngine that never answers (pushServerKey never calls them). */
private fun dummyPushApis(): Pair<PushResources, TimelineResources> {
    val client =
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
        }
    return ktorfit {
        baseUrl("https://example.com/")
        httpClient(client)
        converterFactories(ResponseConverterFactory(), MastodonPagingConverterFactory())
    }.let { it.createPushResources() to it.createTimelineResources() }
}

private fun fakeInstanceResources(
    v2: InstanceData?,
    v1: InstanceInfoV1?,
) = object : InstanceResources {
    override suspend fun instance(): InstanceData = v2 ?: throw NotImplementedError()

    override suspend fun instanceV1(): InstanceInfoV1 = v1 ?: throw NotImplementedError()
}

private fun mastodonPush(instance: InstanceResources): MastodonPush {
    val (resources, timeline) = dummyPushApis()
    return MastodonPush(ACCOUNT_KEY, resources, instance, timeline)
}

class MastodonPushServerKeyTest {
    @Test
    fun v2KeyWins() =
        runTest {
            val push =
                mastodonPush(
                    fakeInstanceResources(
                        v2 = V2_INSTANCE_WITH_KEY,
                        v1 = InstanceInfoV1(uri = "https://mastodon.example"),
                    ),
                )

            assertEquals(PushServerKey.Available("V2_VAPID_KEY"), push.pushServerKey())
        }

    @Test
    fun v2MissFallsBackToV1ConfigurationKey() =
        runTest {
            val push =
                mastodonPush(
                    fakeInstanceResources(
                        v2 = V2_INSTANCE_WITHOUT_KEY,
                        v1 =
                            InstanceInfoV1(
                                uri = "https://mastodon.example",
                                configuration = Configuration(vapid = Vapid(publicKey = "V1_VAPID_KEY")),
                            ),
                    ),
                )

            assertEquals(PushServerKey.Available("V1_VAPID_KEY"), push.pushServerKey())
        }

    @Test
    fun v2FailureFallsBackToAkkomaV1PleromaSnakeCaseKey() =
        runTest {
            // Parsed through JSON so the `pleroma.vapid_public_key` SerialName is really exercised.
            val v1 = AKKOMA_V1_JSON.decodeJson<InstanceInfoV1>()
            assertEquals("BIUU7Ywr2HQlAKKOMA_VAPID_KEY", v1.pleroma?.vapidPublicKeySnake)
            val push =
                mastodonPush(
                    object : InstanceResources {
                        // Akkoma answers GET api/v2/instance with 404, which ktor surfaces as an
                        // ordinary Exception (ClientRequestException).
                        override suspend fun instance(): InstanceData = throw IllegalStateException("HTTP 404")

                        override suspend fun instanceV1(): InstanceInfoV1 = v1
                    },
                )

            assertEquals(PushServerKey.Available("BIUU7Ywr2HQlAKKOMA_VAPID_KEY"), push.pushServerKey())
        }

    @Test
    fun v1PleromaCamelCaseKeyIsAccepted() =
        runTest {
            val push =
                mastodonPush(
                    fakeInstanceResources(
                        v2 = V2_INSTANCE_WITHOUT_KEY,
                        v1 =
                            InstanceInfoV1(
                                uri = "https://pleroma.example",
                                pleroma = PleromaInstanceInfo(vapidPublicKeyCamel = "CAMEL_KEY"),
                            ),
                    ),
                )

            assertEquals(PushServerKey.Available("CAMEL_KEY"), push.pushServerKey())
        }

    @Test
    fun blankKeysAreSkippedInFavourOfLaterCandidates() =
        runTest {
            val push =
                mastodonPush(
                    fakeInstanceResources(
                        v2 = InstanceData(domain = "mastodon.example", configuration = Configuration(vapid = Vapid(publicKey = " "))),
                        v1 =
                            InstanceInfoV1(
                                uri = "https://pleroma.example",
                                pleroma = PleromaInstanceInfo(vapidPublicKeySnake = "SNAKE_KEY"),
                            ),
                    ),
                )

            assertEquals(PushServerKey.Available("SNAKE_KEY"), push.pushServerKey())
        }

    @Test
    fun everythingMissingYieldsDisabled() =
        runTest {
            val push =
                mastodonPush(
                    fakeInstanceResources(
                        v2 = V2_INSTANCE_WITHOUT_KEY,
                        v1 = InstanceInfoV1(uri = "https://gts.example"),
                    ),
                )

            assertEquals(PushServerKey.Disabled, push.pushServerKey())
        }
}
