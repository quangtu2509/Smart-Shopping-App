package com.toked.smartshopping.backend

import com.sun.net.httpserver.HttpServer
import com.toked.smartshopping.backend.catalog.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** JUnit is a blocking boundary; HTTP fixture, fake credentials and no PostgreSQL/provider access. */
class CatalogFlowTest {
    private val key = "fixture-catalog-key-do-not-log"
    private val item = """{"id":17,"product_id":"remote-99","name":"Fixture rice","website":"retailer.example.test",
        "approved":true,"source":"retailer-api","url":"https://retailer.example.test/rice","images":["https://images.example.test/rice.png"],
        "brand":"Fixture brand","sku":"sku-17","unit":"bag","quantity_label":"2 kg","currency":"VND",
        "price":9007199254740993,"api_price":123456.78,"original_price":null,"sale_price":null,
        "price_options":[{"price":"125000.01","quantity_label":"another package"}],"variants":[{"price":42}],
        "availability":"unknown","observed_at":1793110000.123,"last_updated_at":1793100000,"last_success_at":1793110000,
        "stale":false,"categories":[{"id":"cat-1","name":"Rice","url":"https://retailer.example.test/rice-category","parent_id":null}],
        "stores":[],"raw":{"password":"never-import-raw"},"evidence":{"request_url":"never-import-evidence"}}""".trimIndent()
    private fun envelope(items: String = item, total: Long = 1, page: Int = 1, limit: Int = 50) =
        """{"items":[$items],"total":$total,"page":$page,"limit":$limit}"""

    private class Fixture : AutoCloseable {
        val count = AtomicInteger()
        var status = 200
        var body = "{}".toByteArray()
        var contentType = "application/json; charset=utf-8"
        var headers = emptyMap<String, String>()
        var authorization: String? = null
        var query: String? = null
        var method: String? = null
        var delayMillis = 0L
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                count.incrementAndGet()
                authorization = exchange.requestHeaders.getFirst("Authorization")
                query = exchange.requestURI.rawQuery
                method = exchange.requestMethod
                arrived.countDown()
                try {
                    if (delayMillis > 0) release.await(delayMillis, TimeUnit.MILLISECONDS)
                    exchange.responseHeaders.add("Content-Type", contentType)
                    headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: java.io.IOException) { /* A cancelled client may close its socket. */ }
                finally { exchange.close() }
            }
            this.executor = this@Fixture.executor
            start()
        }
        val origin get() = "http://127.0.0.1:${server.address.port}"
        override fun close() { release.countDown(); server.stop(0); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun configurationIsOptionalRedactedAndHttpsOnly() {
        assertNull(CatalogFlowConfig.fromEnvironment(emptyMap()))
        assertFailsWith<IllegalArgumentException> { CatalogFlowConfig.fromEnvironment(mapOf("CATALOGFLOW_BASE_URL" to "https://example.test")) }
        val config = CatalogFlowConfig.fromEnvironment(mapOf("CATALOGFLOW_API_KEY" to key))!!
        assertEquals(CatalogFlowConfig.DEFAULT_ORIGIN, config.origin.toString())
        assertFalse(config.toString().contains(key))
        listOf("http://example.test", "https://user:pass@example.test", "https://example.test/path", "https://example.test/?q=x", "https://example.test/#x", "invalid").forEach {
            val failure = assertFailsWith<IllegalArgumentException> { CatalogFlowConfig.create(it, key) }
            assertFalse(failure.toString().contains(key))
        }
        assertFailsWith<IllegalArgumentException> { CatalogFlowConfig.create("https://example.test", "secret\nheader") }
        assertFailsWith<IllegalArgumentException> { CatalogFlowConfig.fixture("http://example.test", key) }
    }

    @Test fun paginationAndFiltersValidateBeforeNetwork() {
        assertFailsWith<IllegalArgumentException> { CatalogQuery(page = 0) }
        assertFailsWith<IllegalArgumentException> { CatalogQuery(limit = 101) }
        assertFailsWith<IllegalArgumentException> { CatalogQuery(search = "x\n") }
        assertFailsWith<IllegalArgumentException> { CatalogQuery(category = "x".repeat(2001)) }
    }

    @Test fun bearerAndEncodedFiltersUseDocumentedEndpoint(): Unit = runBlocking {
        Fixture().use { f ->
            f.body = envelope(page = 2, limit = 1, total = 2).toByteArray()
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key)).use { client ->
                client.products(CatalogQuery(2, 1, "Sữa & gạo + 1", "https://shop.test/a?b=c", "store/a", "https://shop.test/cat?x=1&y=2"))
            }
            assertEquals("GET", f.method)
            assertEquals("Bearer $key", f.authorization)
            assertFalse(f.query!!.contains(key))
            val params = f.query!!.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
            }
            assertEquals("Sữa & gạo + 1", params["search"])
            assertEquals("https://shop.test/cat?x=1&y=2", params["category"])
            assertEquals("store/a", params["collection"])
            assertEquals("2", params["page"])
            assertEquals(1, f.count.get())
        }
    }

    @Test fun decoderPreservesDecimalAndDoesNotInventStoreOrPriceSemantics() {
        val page = CatalogFlowDecoder().page(envelope(), CatalogQuery())
        val p = page.items.single()
        assertEquals("9007199254740993", p.price!!.toPlainString())
        assertEquals("123456.78", p.apiPrice!!.toPlainString())
        assertEquals("125000.01", p.priceOptions.single().price!!.toPlainString())
        assertEquals("1793110000.123", p.observedAt!!.toPlainString())
        assertEquals("2 kg", p.quantityLabel)
        assertEquals("cat-1", p.categories.single().id)
        assertEquals(1, p.variantCount)
        assertFalse(p.hasStoreMetadata)
        assertNull(p.salePrice)
        assertFalse(p.toString().contains("never-import"))
        assertFalse(page.partial)
    }

    @Test fun missingAndInvalidPricesRemainNullWhileZeroIsPreserved() {
        val obj = Json.parseToJsonElement(item).jsonObject
        fun parsed(price: JsonElement): ProviderProduct {
            val changed = JsonObject(obj + mapOf("price" to price, "source" to JsonNull, "observed_at" to JsonNull, "currency" to JsonNull))
            return CatalogFlowDecoder().page(envelope(changed.toString()), CatalogQuery()).items.single()
        }
        assertNull(parsed(JsonNull).price)
        assertNull(parsed(JsonPrimitive("not a price")).price)
        assertNull(parsed(JsonPrimitive(-1)).price)
        assertEquals("0", parsed(JsonPrimitive(0)).price!!.toPlainString())
        assertTrue(parsed(JsonNull).warnings.containsAll(listOf("MISSING_PRICE", "MISSING_SOURCE", "MISSING_OBSERVED_AT", "MISSING_CURRENCY")))
    }

    @Test fun invalidOrUnapprovedItemsAreRejectedWithoutDiscardingValidItems() {
        val unapproved = item.replace("\"approved\":true", "\"approved\":false")
        val wrongBool = item.replace("\"approved\":true", "\"approved\":\"true\"")
        val page = CatalogFlowDecoder().page(envelope("$item,$unapproved,$wrongBool,{},null", 5), CatalogQuery())
        assertEquals(1, page.items.size)
        assertEquals(4, page.rejectedItems)
        assertTrue(page.partial)
    }

    @Test fun duplicatesAreScopedByWebsiteAndNeverCrossMatchedByName() {
        val second = item.replace("retailer.example.test", "another.example.test")
        val page = CatalogFlowDecoder().page(envelope("$item,$item,$second", 3), CatalogQuery())
        assertEquals(2, page.items.size)
        assertTrue("DUPLICATE_PROVIDER_ITEMS" in page.warnings)
    }

    @Test fun unsafeLinksAndUnexpectedRawFieldsAreExcluded() {
        val obj = Json.parseToJsonElement(item).jsonObject
        val changed = JsonObject(obj + mapOf("url" to JsonPrimitive("javascript:alert(1)"), "images" to JsonArray(listOf(JsonPrimitive("https://user:pass@example.test/image"), JsonPrimitive("file:///private")))))
        val p = CatalogFlowDecoder().page(envelope(changed.toString()), CatalogQuery()).items.single()
        assertNull(p.url)
        assertTrue(p.images.isEmpty())
    }

    @Test fun emptyAndMalformedEnvelopeAreDistinct() {
        val empty = CatalogFlowDecoder().page(envelope("", 0), CatalogQuery())
        assertEquals(0L, empty.total)
        assertFalse(empty.partial)
        listOf("<html>not json</html>", "[]", "{}", "{\"items\":{},\"total\":0,\"page\":1,\"limit\":50}",
            envelope(total = -1), envelope(page = 2), envelope(limit = 99)).forEach {
            assertEquals(ProviderFailureCode.INVALID_RESPONSE, assertFailsWith<ProviderFailure> { CatalogFlowDecoder().page(it, CatalogQuery()) }.code)
        }
    }

    @Test fun upstreamStatusesAreSafeAndNeverAutomaticallyRetried(): Unit = runBlocking {
        val cases = mapOf(401 to ProviderFailureCode.AUTHENTICATION, 403 to ProviderFailureCode.FORBIDDEN,
            429 to ProviderFailureCode.RATE_LIMITED, 503 to ProviderFailureCode.UNAVAILABLE, 404 to ProviderFailureCode.INVALID_RESPONSE)
        cases.forEach { (status, expected) ->
            Fixture().use { f ->
                f.status = status; f.body = "upstream-body-$key".toByteArray(); f.headers = mapOf("Retry-After" to "17")
                CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key)).use { client ->
                    val failure = assertFailsWith<ProviderFailure> { client.products() }
                    assertEquals(expected, failure.code)
                    assertEquals(status, failure.upstreamStatus)
                    assertNull(failure.cause)
                    assertFalse(failure.stackTraceToString().contains(key))
                    if (status == 429 || status == 503) assertEquals(17, failure.retryAfterSeconds)
                    assertEquals(1, f.count.get())
                }
            }
        }
    }

    @Test fun retryAfterHttpDateIsParsedWithInjectedClock(): Unit = runBlocking {
        Fixture().use { f ->
            f.status = 429; f.headers = mapOf("Retry-After" to "Tue, 27 Oct 2026 12:00:20 GMT")
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key), Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC)).use { client ->
                assertEquals(20, assertFailsWith<ProviderFailure> { client.products() }.retryAfterSeconds)
            }
        }
    }

    @Test fun redirectsNeverForwardBearerToAnotherHost(): Unit = runBlocking {
        Fixture().use { destination -> Fixture().use { source ->
            source.status = 302; source.headers = mapOf("Location" to destination.origin)
            CatalogFlowClient(CatalogFlowConfig.fixture(source.origin, key)).use { client ->
                assertEquals(ProviderFailureCode.REDIRECT, assertFailsWith<ProviderFailure> { client.products() }.code)
            }
            assertEquals(0, destination.count.get())
        } }
    }

    @Test fun oversizedBodyIsCancelledBeforeBeingBuffered(): Unit = runBlocking {
        Fixture().use { f ->
            f.body = "x".repeat(16384).toByteArray()
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key, maxBytes = 128)).use { client ->
                assertEquals(ProviderFailureCode.RESPONSE_TOO_LARGE, assertFailsWith<ProviderFailure> { client.products() }.code)
            }
        }
    }

    @Test fun invalidContentTypeAndUtf8AreNotAccepted(): Unit = runBlocking {
        Fixture().use { f ->
            f.contentType = "text/html"
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key)).use { client ->
                assertEquals(ProviderFailureCode.INVALID_RESPONSE, assertFailsWith<ProviderFailure> { client.products() }.code)
                f.contentType = "application/json"; f.body = byteArrayOf(0xC3.toByte(), 0x28)
                assertEquals(ProviderFailureCode.INVALID_RESPONSE, assertFailsWith<ProviderFailure> { client.products() }.code)
            }
        }
    }

    @Test fun timeoutAndCoroutineCancellationAreDifferent(): Unit = runBlocking {
        Fixture().use { f ->
            f.delayMillis = 5000
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key, Duration.ofMillis(150))).use { client ->
                assertEquals(ProviderFailureCode.TIMEOUT, assertFailsWith<ProviderFailure> { client.products() }.code)
            }
        }
        Fixture().use { f ->
            f.delayMillis = 5000
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key, Duration.ofSeconds(10))).use { client ->
                val job = async { client.products() }
                withContext(Dispatchers.IO) { assertTrue(f.arrived.await(3, TimeUnit.SECONDS)) }
                job.cancel()
                assertFailsWith<CancellationException> { job.await() }
            }
        }
    }

    @Test fun refusedConnectionHasSafeNetworkFailure(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        CatalogFlowClient(CatalogFlowConfig.fixture("http://127.0.0.1:$port", key)).use { client ->
            val failure = assertFailsWith<ProviderFailure> { client.products() }
            assertEquals(ProviderFailureCode.NETWORK, failure.code)
            assertTrue(failure.retryable)
        }
    }

    @Test fun runtimeCheckUsesOnlyOneProductAndDoesNotPrintProductValues(): Unit = runBlocking {
        Fixture().use { f ->
            f.body = envelope(limit = 1, total = 13481).toByteArray()
            CatalogFlowClient(CatalogFlowConfig.fixture(f.origin, key)).use { client ->
                val summary = checkCatalogConnection(client)
                assertTrue(summary.contains("total=13481; decoded=1"))
                assertFalse(summary.contains("Fixture rice"))
                assertFalse(summary.contains(key))
                assertEquals("page=1&limit=1", f.query)
            }
        }
    }
}
