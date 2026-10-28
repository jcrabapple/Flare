package dev.dimension.flare.data.datasource.mastodon

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.network.mastodon.api.InstanceResources
import dev.dimension.flare.data.network.mastodon.api.PushResources
import dev.dimension.flare.data.network.mastodon.api.TimelineResources
import dev.dimension.flare.data.network.mastodon.api.createPushResources
import dev.dimension.flare.data.network.mastodon.api.createTimelineResources
import dev.dimension.flare.data.network.mastodon.api.model.InstanceData
import dev.dimension.flare.data.network.mastodon.api.model.InstanceInfoV1
import dev.dimension.flare.data.network.mastodon.api.model.MastodonPagingConverterFactory
import dev.dimension.flare.data.network.mastodon.installMastodonErrorMapping
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationType
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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

private class Captured(
    val method: HttpMethod,
    val path: String,
    val query: String,
)

/**
 * Builds a MastodonPush over a MockEngine ktorfit, mirroring MastodonService's configuration
 * (expectSuccess = true, plus the production error-mapping validator when requested).
 */
private fun mockPush(
    responses: List<Pair<HttpStatusCode, String>>,
    captured: MutableList<Captured>,
    productionErrorMapping: Boolean = false,
): MastodonPush {
    val client =
        HttpClient(MockEngine) {
            expectSuccess = true
            if (productionErrorMapping) {
                installMastodonErrorMapping()
            }
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
            engine {
                addHandler { request ->
                    captured.add(
                        Captured(
                            method = request.method,
                            path = request.url.encodedPath,
                            query = request.url.encodedQuery,
                        ),
                    )
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
            baseUrl("https://example.com/")
            httpClient(client)
            converterFactories(ResponseConverterFactory(), MastodonPagingConverterFactory())
        }
    return MastodonPush(
        accountKey = ACCOUNT_KEY,
        resources = builder.createPushResources(),
        instance = NO_INSTANCE,
        timeline = builder.createTimelineResources(),
    )
}

private val NOTIFICATION_JSON =
    """
    [{
      "id": "109843190626423965",
      "type": "mention",
      "created_at": "2023-02-17T19:30:00.000Z",
      "account": {
        "id": "109803394899488999",
        "username": "testuser",
        "acct": "testuser@example.com",
        "display_name": "Test User",
        "url": "https://example.com/@testuser",
        "avatar": "https://files.example.com/avatar.png"
      },
      "status": {
        "id": "109843190626423984",
        "created_at": "2023-02-17T19:30:00.000Z",
        "visibility": "public",
        "uri": "https://example.com/users/testuser/statuses/109843190626423984",
        "content": "<p>Hello from the docs!</p>",
        "account": {
          "id": "109803394899488999",
          "username": "testuser",
          "acct": "testuser@example.com"
        }
      }
    }]
    """.trimIndent()

class MastodonPushFetchTest {
    @Test
    fun fetchNotificationsSinceGetsSinceIdAndLimitAndMapsItems() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push = mockPush(listOf(HttpStatusCode.OK to NOTIFICATION_JSON), captured)

            val items = push.fetchNotificationsSince(sinceId = "1", limit = 30)

            val request = captured.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/notifications", request.path)
            assertTrue("since_id=1" in request.query, "since_id=1 expected in ${request.query}")
            assertTrue("limit=30" in request.query, "limit=30 expected in ${request.query}")
            assertEquals(1, items.size)
            assertEquals("109843190626423965", items.single().notificationId)
            assertEquals(PushNotificationType.Mention, items.single().type)
        }

    @Test
    fun latestNotificationIdTakesFirstNotification() =
        runTest {
            val captured = mutableListOf<Captured>()
            val push = mockPush(listOf(HttpStatusCode.OK to NOTIFICATION_JSON), captured)

            assertEquals("109843190626423965", push.latestNotificationId())

            val request = captured.single()
            assertEquals("/api/v1/notifications", request.path)
            assertTrue("limit=1" in request.query, "limit=1 expected in ${request.query}")
        }

    @Test
    fun fetchNotificationsSinceMapsUnauthorizedToReauthRequired() =
        runTest {
            val push = mockPush(listOf(HttpStatusCode.Unauthorized to "unauthorized"), mutableListOf())

            assertFailsWith<PushReauthRequiredException> {
                push.fetchNotificationsSince(null, 30)
            }
        }

    @Test
    fun latestNotificationIdMapsUnauthorizedToReauthRequired() =
        runTest {
            val push = mockPush(listOf(HttpStatusCode.Unauthorized to "unauthorized"), mutableListOf())

            assertFailsWith<PushReauthRequiredException> {
                push.latestNotificationId()
            }
        }

    // Real servers answer with JSON error bodies, which MastodonService's validator rethrows as
    // MastodonException carrying the HTTP status; these use the production validator.
    @Test
    fun fetchNotificationsSinceMapsForbiddenJsonErrorBodyToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(HttpStatusCode.Forbidden to """{"error":"Invalid credentials."}"""),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<PushReauthRequiredException> {
                push.fetchNotificationsSince(null, 30)
            }
        }

    @Test
    fun latestNotificationIdMapsForbiddenJsonErrorBodyToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(HttpStatusCode.Forbidden to """{"error":"Invalid credentials."}"""),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<PushReauthRequiredException> {
                push.latestNotificationId()
            }
        }

    @Test
    fun fetchNotificationsSincePropagatesOtherErrorStatuses() =
        runTest {
            val push =
                mockPush(
                    listOf(HttpStatusCode.InternalServerError to """{"error":"Boom"}"""),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<Exception> {
                push.fetchNotificationsSince(null, 30)
            }.let { assertTrue(it !is PushReauthRequiredException) }
        }

    // A token that lost the push scope answers the category PUT with 403; it must surface as reauth.
    @Test
    fun updatePushCategoriesMapsForbiddenToReauthRequired() =
        runTest {
            val push = mockPush(listOf(HttpStatusCode.Forbidden to "forbidden"), mutableListOf())

            assertFailsWith<PushReauthRequiredException> {
                push.updatePushCategories(setOf(PushCategory.Mention))
            }
        }

    @Test
    fun updatePushCategoriesMapsForbiddenJsonErrorBodyToReauthRequired() =
        runTest {
            val push =
                mockPush(
                    listOf(HttpStatusCode.Forbidden to """{"error":"This action is outside the authorized scopes"}"""),
                    mutableListOf(),
                    productionErrorMapping = true,
                )

            assertFailsWith<PushReauthRequiredException> {
                push.updatePushCategories(setOf(PushCategory.Mention))
            }
        }
}
