package dev.dimension.flare.data.network.mastodon.api

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datasource.mastodon.MastodonPush
import dev.dimension.flare.data.network.mastodon.api.model.InstanceData
import dev.dimension.flare.data.network.mastodon.api.model.InstanceInfoV1
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val ACCOUNT_KEY = MicroBlogKey(id = "1", host = "mastodon.social")

private val NO_INSTANCE =
    object : InstanceResources {
        override suspend fun instance(): InstanceData = throw NotImplementedError()

        override suspend fun instanceV1(): InstanceInfoV1 = throw NotImplementedError()
    }

private val SUBSCRIPTION_JSON =
    """
    {
      "id": "01J4Z8K7Q2M3N4P5R6S7T8V9W0",
      "endpoint": "https://ntfy.sh/flaretopic",
      "server_key": "BCk-QqERU0q-CfYZjcuB6lnyyOYfJ2AifKqfeGIm7Z-HiTU5T9eTG5GxVA0_OH5mMlI4UkkDTpaZwozy0TzdZ2M=",
      "alerts": {"mention": true, "follow": false},
      "policy": "all"
    }
    """.trimIndent()

private class CapturedRequest(
    val method: HttpMethod,
    val path: String,
    val fields: Map<String, String>,
)

private fun mockPushResources(
    status: HttpStatusCode,
    body: String,
    capture: (CapturedRequest) -> Unit,
): PushResources =
    mockPushResources(
        responses = listOf(status to body),
        capture = capture,
    )

private fun mockPushResources(
    responses: List<Pair<HttpStatusCode, String>>,
    capture: (CapturedRequest) -> Unit,
): PushResources {
    val client =
        HttpClient(MockEngine) {
            expectSuccess = true
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
            engine {
                addHandler { request ->
                    capture(
                        CapturedRequest(
                            method = request.method,
                            path = request.url.encodedPath,
                            fields = request.formFields(),
                        ),
                    )
                    val (status, body) = responses.first()
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            }
        }
    return ktorfit {
        baseUrl("https://example.com/")
        httpClient(client)
        converterFactories(ResponseConverterFactory())
    }.createPushResources()
}

private fun HttpRequestData.formFields(): Map<String, String> =
    when (val content = body) {
        is FormDataContent -> {
            content.formData.entries().associate { (name, values) -> name to values.first() }
        }

        is OutgoingContent.ByteArrayContent -> {
            content
                .bytes()
                .decodeToString()
                .split('&')
                .filter { it.isNotEmpty() }
                .associate {
                    val (name, value) = it.split('=', limit = 2)
                    name.decodeURLPart() to value.decodeURLPart()
                }
        }

        is OutgoingContent.NoContent -> {
            emptyMap()
        }

        else -> {
            error("Unexpected request body: ${content::class.simpleName}")
        }
    }

class PushResourcesTest {
    @Test
    fun subscribePostSendsExactFormFieldsForMentionAndReblogCategories() =
        runTest {
            lateinit var captured: CapturedRequest
            val resources =
                mockPushResources(
                    status = HttpStatusCode.OK,
                    body = SUBSCRIPTION_JSON,
                    capture = { captured = it },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            push.subscribePush(
                endpoint = "https://ntfy.example/topic",
                p256dh = "P256DH_KEY",
                auth = "AUTH_KEY",
                categories = setOf(PushCategory.Mention, PushCategory.Reblog),
            )

            assertEquals(HttpMethod.Post, captured.method)
            assertEquals("/api/v1/push/subscription", captured.path)
            assertEquals(
                mapOf(
                    "subscription[endpoint]" to "https://ntfy.example/topic",
                    "subscription[keys][p256dh]" to "P256DH_KEY",
                    "subscription[keys][auth]" to "AUTH_KEY",
                    "subscription[standard]" to "true",
                    "data[policy]" to "all",
                    "data[alerts][mention]" to "true",
                    "data[alerts][status]" to "false",
                    "data[alerts][reblog]" to "true",
                    "data[alerts][follow]" to "false",
                    "data[alerts][follow_request]" to "false",
                    "data[alerts][favourite]" to "false",
                    "data[alerts][poll]" to "false",
                    "data[alerts][update]" to "false",
                    "data[alerts][quote]" to "true",
                ),
                captured.fields,
            )
        }

    @Test
    fun updatePushCategoriesPutsOnlyDataFields() =
        runTest {
            lateinit var captured: CapturedRequest
            val resources =
                mockPushResources(
                    status = HttpStatusCode.OK,
                    body = SUBSCRIPTION_JSON,
                    capture = { captured = it },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            push.updatePushCategories(setOf(PushCategory.Follow, PushCategory.Poll))

            assertEquals(HttpMethod.Put, captured.method)
            assertEquals("/api/v1/push/subscription", captured.path)
            assertTrue(captured.fields.keys.none { it.startsWith("subscription") })
            assertEquals(
                mapOf(
                    "data[policy]" to "all",
                    "data[alerts][mention]" to "false",
                    "data[alerts][status]" to "false",
                    "data[alerts][reblog]" to "false",
                    "data[alerts][follow]" to "true",
                    "data[alerts][follow_request]" to "false",
                    "data[alerts][favourite]" to "false",
                    "data[alerts][poll]" to "true",
                    "data[alerts][update]" to "false",
                    "data[alerts][quote]" to "false",
                ),
                captured.fields,
            )
        }

    @Test
    fun getSubscriptionParsesGoToSocialStringUlid() =
        runTest {
            val resources =
                mockPushResources(
                    status = HttpStatusCode.OK,
                    body = SUBSCRIPTION_JSON,
                    capture = { },
                )

            val subscription = resources.pushSubscription()

            assertEquals("01J4Z8K7Q2M3N4P5R6S7T8V9W0", subscription.id)
            assertEquals("https://ntfy.sh/flaretopic", subscription.endpoint)
            assertEquals(
                "BCk-QqERU0q-CfYZjcuB6lnyyOYfJ2AifKqfeGIm7Z-HiTU5T9eTG5GxVA0_OH5mMlI4UkkDTpaZwozy0TzdZ2M=",
                subscription.serverKey,
            )
            assertEquals(mapOf("mention" to true, "follow" to false), subscription.alerts)
        }

    @Test
    fun subscribeMapsUnauthorizedToPushReauthRequiredException() =
        runTest {
            val resources =
                mockPushResources(
                    status = HttpStatusCode.Unauthorized,
                    body = "unauthorized",
                    capture = { },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            assertFailsWith<PushReauthRequiredException> {
                push.subscribePush(
                    endpoint = "https://ntfy.example/topic",
                    p256dh = "P256DH_KEY",
                    auth = "AUTH_KEY",
                    categories = setOf(PushCategory.Mention),
                )
            }
        }

    @Test
    fun subscribeMapsForbiddenToPushReauthRequiredException() =
        runTest {
            val resources =
                mockPushResources(
                    status = HttpStatusCode.Forbidden,
                    body = "forbidden",
                    capture = { },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            assertFailsWith<PushReauthRequiredException> {
                push.subscribePush(
                    endpoint = "https://ntfy.example/topic",
                    p256dh = "P256DH_KEY",
                    auth = "AUTH_KEY",
                    categories = setOf(PushCategory.Mention),
                )
            }
        }

    @Test
    fun subscribePropagatesNonReauthStatuses() =
        runTest {
            val resources =
                mockPushResources(
                    status = HttpStatusCode.UnprocessableEntity,
                    body = "unprocessable",
                    capture = { },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            assertFailsWith<Exception> {
                push.subscribePush(
                    endpoint = "https://ntfy.example/topic",
                    p256dh = "P256DH_KEY",
                    auth = "AUTH_KEY",
                    categories = setOf(PushCategory.Mention),
                )
            }.let { assertTrue(it !is PushReauthRequiredException) }
        }

    @Test
    fun unsubscribePushSwallows404() =
        runTest {
            val resources =
                mockPushResources(
                    status = HttpStatusCode.NotFound,
                    body = "not found",
                    capture = { },
                )
            val push = MastodonPush(ACCOUNT_KEY, resources, NO_INSTANCE)

            push.unsubscribePush("https://ntfy.example/topic")
        }
}
