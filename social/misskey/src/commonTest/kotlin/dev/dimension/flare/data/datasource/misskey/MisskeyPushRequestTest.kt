package dev.dimension.flare.data.datasource.misskey

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.network.misskey.api.createAccountApi
import dev.dimension.flare.data.network.misskey.api.createMetaApi
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.data.push.PushServerKey
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val ACCOUNT_KEY = MicroBlogKey(id = "1", host = "misskey.example")

private class Captured(
    val method: HttpMethod,
    val path: String,
    val body: String?,
)

private fun mockPush(
    responses: List<Pair<HttpStatusCode, String>>,
    captured: MutableList<Captured>,
): MisskeyPush {
    val client =
        HttpClient(MockEngine) {
            expectSuccess = true
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
            install(DefaultRequest) {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            engine {
                addHandler { request ->
                    captured.add(request.captured())
                    val (status, body) =
                        responses.getOrNull(captured.size - 1)
                            ?: responses.last()
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            }
        }
    val builder =
        ktorfit {
            baseUrl("https://misskey.example/api/")
            httpClient(client)
            converterFactories(ResponseConverterFactory())
        }
    return MisskeyPush(
        accountKey = ACCOUNT_KEY,
        accountApi = builder.createAccountApi(),
        metaApi = builder.createMetaApi(),
    )
}

private fun HttpRequestData.captured(): Captured =
    Captured(
        method = method,
        path = url.encodedPath,
        body =
            when (val content = body) {
                is TextContent -> content.text
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> null
            },
    )

private val FOLLOW_NOTIFICATION_JSON =
    """
    {
      "id": "n1",
      "createdAt": "2023-02-17T19:33:00.000Z",
      "type": "follow",
      "user": { "id": "u9", "username": "someone", "name": "Someone" }
    }
    """.trimIndent()

private val SW_REGISTER_RESPONSE_JSON =
    """
    {
      "userId": "u1",
      "endpoint": "https://ntfy.example/flare-topic",
      "sendReadMessage": false
    }
    """.trimIndent()

class MisskeyPushRequestTest {
    @Test
    fun fetchNotificationsSincePostsSinceIdAndNeverMarksRead() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.OK to
                                """
                                [{
                                  "id": "n2",
                                  "createdAt": "2023-02-17T19:34:00.000Z",
                                  "type": "follow",
                                  "user": { "id": "u9", "username": "someone" }
                                }]
                                """.trimIndent(),
                        ),
                    captured = captured,
                )

            val items = push.fetchNotificationsSince(sinceId = "n1", limit = 30)

            val request = captured.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/i/notifications", request.path)
            assertTrue("\"markAsRead\":false" in request.body!!, "markAsRead=false expected in ${request.body}")
            assertTrue("\"sinceId\":\"n1\"" in request.body, "sinceId=n1 expected in ${request.body}")
            assertTrue("\"limit\":30" in request.body, "limit=30 expected in ${request.body}")
            assertEquals(1, items.size)
            assertEquals("n2", items.single().notificationId)
            assertEquals(PushNotificationType.Follow, items.single().type)
        }

    @Test
    fun latestNotificationIdTakesFirstNotification() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.OK to
                                "[$FOLLOW_NOTIFICATION_JSON,{\"id\":\"n0\",\"createdAt\":\"2023-02-17T19:00:00.000Z\",\"type\":\"follow\"}]",
                        ),
                    captured = captured,
                )

            val latest = push.latestNotificationId()

            assertEquals("n1", latest)
            assertEquals("/api/i/notifications", captured.single().path)
            assertTrue("\"limit\":1" in captured.single().body!!, "limit=1 expected")
            assertTrue("\"markAsRead\":false" in captured.single().body!!, "markAsRead=false expected")
        }

    @Test
    fun pushServerKeyAvailableWhenServiceWorkerEnabledWithKey() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.OK to """{"enableServiceWorker": true, "swPublickey": "BCVAPIDKEY"}""",
                        ),
                    captured = captured,
                )

            val key = push.pushServerKey()

            assertEquals(PushServerKey.Available("BCVAPIDKEY"), key)
            assertEquals("/api/meta", captured.single().path)
        }

    @Test
    fun pushServerKeyDisabledWhenServiceWorkerDisabled() =
        runTest {
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.OK to """{"enableServiceWorker": false, "swPublickey": "BCVAPIDKEY"}""",
                        ),
                    captured = mutableListOf(),
                )

            assertEquals(PushServerKey.Disabled, push.pushServerKey())
        }

    @Test
    fun pushServerKeyDisabledWhenKeyIsBlank() =
        runTest {
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.OK to """{"enableServiceWorker": true, "swPublickey": " "}""",
                        ),
                    captured = mutableListOf(),
                )

            assertEquals(PushServerKey.Disabled, push.pushServerKey())
        }

    @Test
    fun subscribePushRegistersWithEndpointKeysAndNoReadMessages() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.OK to SW_REGISTER_RESPONSE_JSON),
                    captured = captured,
                )

            push.subscribePush(
                endpoint = "https://ntfy.example/flare-topic",
                p256dh = "P256DHKEY",
                auth = "AUTHSECRET",
                categories = setOf(PushCategory.Mention),
            )

            val request = captured.single()
            assertEquals("/api/sw/register", request.path)
            val body = request.body!!
            assertTrue("\"endpoint\":\"https://ntfy.example/flare-topic\"" in body, "endpoint expected in $body")
            assertTrue("\"publickey\":\"P256DHKEY\"" in body, "publickey expected in $body")
            assertTrue("\"auth\":\"AUTHSECRET\"" in body, "auth expected in $body")
            // sendReadMessage=false is the SwRegisterRequest schema default, so kotlinx JSON (encodeDefaults=false)
            // elides it; the Misskey server default for an omitted flag is false, which is what we want.
            assertTrue("\"sendReadMessage\":true" !in body, "sendReadMessage must not be true in $body")
        }

    @Test
    fun subscribePushRejectsNonHttpsEndpointWithoutCallingServer() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push = mockPush(emptyList(), captured)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    push.subscribePush(
                        endpoint = "http://ntfy.example/flare-topic",
                        p256dh = "P256DHKEY",
                        auth = "AUTHSECRET",
                        categories = emptySet(),
                    )
                }

            assertEquals("Misskey requires an https push endpoint", error.message)
            assertTrue(captured.isEmpty(), "no request should reach the server")
        }

    @Test
    fun subscribePushMapsUnauthorizedToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.Unauthorized to "{}"),
                    captured = mutableListOf(),
                )

            assertFailsWith<PushReauthRequiredException> {
                push.subscribePush(
                    endpoint = "https://ntfy.example/flare-topic",
                    p256dh = "P256DHKEY",
                    auth = "AUTHSECRET",
                    categories = emptySet(),
                )
            }
        }

    @Test
    fun subscribePushMapsForbiddenToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.Forbidden to "{}"),
                    captured = mutableListOf(),
                )

            assertFailsWith<PushReauthRequiredException> {
                push.subscribePush(
                    endpoint = "https://ntfy.example/flare-topic",
                    p256dh = "P256DHKEY",
                    auth = "AUTHSECRET",
                    categories = emptySet(),
                )
            }
        }

    @Test
    fun unsubscribePushSwallows404() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.NotFound to "{}"),
                    captured = captured,
                )

            push.unsubscribePush("https://ntfy.example/flare-topic")

            assertEquals("/api/sw/unregister", captured.single().path)
            assertTrue(
                "\"endpoint\":\"https://ntfy.example/flare-topic\"" in captured.single().body!!,
                "endpoint expected in body",
            )
        }

    @Test
    fun unsubscribePushPropagatesOtherErrors() =
        runTest {
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.InternalServerError to "{}"),
                    captured = mutableListOf(),
                )

            assertFailsWith<ResponseException> {
                push.unsubscribePush("https://ntfy.example/flare-topic")
            }
        }
}
