package dev.dimension.flare.data.datasource.misskey

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.network.misskey.api.createAccountApi
import dev.dimension.flare.data.network.misskey.api.createMetaApi
import dev.dimension.flare.data.network.misskey.api.model.MisskeyException
import dev.dimension.flare.data.network.misskey.api.model.SwRegisterRequest
import dev.dimension.flare.data.network.misskey.installMisskeyErrorMapping
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
    productionErrorMapping: Boolean = false,
): MisskeyPush {
    val client =
        HttpClient(MockEngine) {
            // MisskeyService does not set expectSuccess; its validator alone turns error bodies
            // into exceptions, so the production-mapping variant must not add ResponseException.
            expectSuccess = !productionErrorMapping
            if (productionErrorMapping) {
                installMisskeyErrorMapping(ACCOUNT_KEY)
            }
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
            // Pin the model default explicitly: with encodeDefaults=false the wire test above cannot
            // tell a `false` value from a dropped field.
            assertEquals(
                false,
                SwRegisterRequest(
                    endpoint = "https://ntfy.example/flare-topic",
                    auth = "AUTHSECRET",
                    publickey = "P256DHKEY",
                ).sendReadMessage,
            )
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
    fun subscribePushAcceptsUppercaseHttpsScheme() =
        runTest {
            // ignoreCase = true: HTTPS:// is a valid https scheme for the local pre-check.
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses = listOf(HttpStatusCode.OK to SW_REGISTER_RESPONSE_JSON),
                    captured = captured,
                )

            push.subscribePush(
                endpoint = "HTTPS://ntfy.example/flare-topic",
                p256dh = "P256DHKEY",
                auth = "AUTHSECRET",
                categories = emptySet(),
            )

            assertEquals("/api/sw/register", captured.single().path)
        }

    @Test
    fun subscribePushRejectsEndpointWithUserInfoWithoutCallingServer() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push = mockPush(emptyList(), captured)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    push.subscribePush(
                        endpoint = "https://user@ntfy.example/flare-topic",
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
    fun unsubscribePushSwallowsJsonErrorBody404() =
        runTest {
            // Real Misskey servers answer a gone registration with a JSON error body, which the
            // production validator rethrows as MisskeyException carrying the HTTP status.
            val captured = mutableListOf<Captured>()
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.NotFound to
                                """{"error":{"message":"No such registration.","code":"NO_SUCH_REGISTRATION","id":"0141126a-b4de-4d4d-a1c8-9c5b1b1e7c7c","kind":"client"}}""",
                        ),
                    captured = captured,
                    productionErrorMapping = true,
                )

            push.unsubscribePush("https://ntfy.example/flare-topic")

            assertEquals("/api/sw/unregister", captured.single().path)
        }

    @Test
    fun unsubscribePushPropagatesOtherJsonErrorBodies() =
        runTest {
            val push =
                mockPush(
                    responses =
                        listOf(
                            HttpStatusCode.InternalServerError to
                                """{"error":{"message":"Internal error occurred.","code":"INTERNAL_ERROR","id":"5d37dbcb-891e-41ca-a3d6-6a3429261f26","kind":"server"}}""",
                        ),
                    captured = mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<MisskeyException> {
                push.unsubscribePush("https://ntfy.example/flare-topic")
            }
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

    // Real Misskey servers answer errors with JSON bodies that MisskeyService's validator rethrows as
    // MisskeyException / RequireReLoginException; these tests use the production validator.
    private suspend fun assertSubscribeNeedsReauth(
        status: HttpStatusCode,
        body: String,
    ) {
        val push = mockPush(listOf(status to body), mutableListOf(), productionErrorMapping = true)
        assertFailsWith<PushReauthRequiredException> {
            push.subscribePush("https://ntfy.example/flare-topic", "P256DH", "AUTH", setOf(PushCategory.Mention))
        }
    }

    @Test
    fun subscribePushMapsPermissionDeniedBodyToReauthRequired() =
        runTest {
            assertSubscribeNeedsReauth(
                HttpStatusCode.Forbidden,
                """{"error":{"message":"Your app does not have the necessary permissions to use this endpoint.","code":"PERMISSION_DENIED","id":"1370e5b7-d4eb-4566-bb1d-7748ee6a1838","kind":"client"}}""",
            )
        }

    @Test
    fun subscribePushMapsAuthenticationFailedBodyToReauthRequired() =
        runTest {
            assertSubscribeNeedsReauth(
                HttpStatusCode.Unauthorized,
                """{"error":{"message":"Authentication failed. Please ensure your token is correct.","code":"AUTHENTICATION_FAILED","id":"b0a7f5f8-dc2f-4171-b91f-de88ad238e14","kind":"client"}}""",
            )
        }

    @Test
    fun subscribePushPropagatesOtherErrorBodies() =
        runTest {
            val push =
                mockPush(
                    listOf(
                        HttpStatusCode.BadRequest to
                            """{"error":{"message":"Invalid param.","code":"INVALID_PARAM","id":"3d81ceae-475f-4600-b2a8-2bc116157532","kind":"client"}}""",
                    ),
                    mutableListOf(),
                    productionErrorMapping = true,
                )
            assertFailsWith<Exception> {
                push.subscribePush("https://ntfy.example/flare-topic", "P256DH", "AUTH", setOf(PushCategory.Mention))
            }.let { assertTrue(it !is PushReauthRequiredException) }
        }

    // The fetch path must map a revoked credential to PushReauthRequiredException with the same
    // mapping subscribePush uses, so the repository can flip the account to ReauthRequired.
    @Test
    fun fetchNotificationsSinceMapsPermissionDeniedBodyToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(
                        HttpStatusCode.Forbidden to
                            """{"error":{"message":"Your app does not have the necessary permissions to use this endpoint.","code":"PERMISSION_DENIED","id":"1370e5b7-d4eb-4566-bb1d-7748ee6a1838","kind":"client"}}""",
                    ),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<PushReauthRequiredException> {
                push.fetchNotificationsSince(sinceId = "n1", limit = 30)
            }
        }

    @Test
    fun latestNotificationIdMapsAuthenticationFailedBodyToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(
                        HttpStatusCode.Unauthorized to
                            """{"error":{"message":"Authentication failed. Please ensure your token is correct.","code":"AUTHENTICATION_FAILED","id":"b0a7f5f8-dc2f-4171-b91f-de88ad238e14","kind":"client"}}""",
                    ),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<PushReauthRequiredException> {
                push.latestNotificationId()
            }
        }

    @Test
    fun fetchNotificationsSinceMapsRawUnauthorizedToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(HttpStatusCode.Unauthorized to "{}"),
                    mutableListOf(),
                )

            assertFailsWith<PushReauthRequiredException> {
                push.fetchNotificationsSince(sinceId = "n1", limit = 30)
            }
        }

    @Test
    fun fetchNotificationsSincePropagatesOtherErrorBodies() =
        runTest {
            val push =
                mockPush(
                    listOf(
                        HttpStatusCode.InternalServerError to
                            """{"error":{"message":"Internal error occurred.","code":"INTERNAL_ERROR","id":"5d37dbcb-891e-41ca-a3d6-6a3429261f26","kind":"server"}}""",
                    ),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<Exception> {
                push.fetchNotificationsSince(sinceId = "n1", limit = 30)
            }.let { assertTrue(it !is PushReauthRequiredException) }
        }
}
