package com.toked.smartshopping.backend

import com.sun.net.httpserver.HttpServer
import com.toked.smartshopping.backend.catalog.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fixture JSON is trimmed from real CrawlData `/api/v1` responses (2026-11). Loopback HTTP only; no real service. */
class CrawlDataTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC)

    private val stores = """[
        {"id":1,"retailer":"emart","external_id":"sala","name":"Emart Sala","enabled":true,"pricing_scope":"branch","pricing_context":null,"ready":true,"reason":null},
        {"id":4,"retailer":"bhx","external_id":"shared","name":"Bách Hóa Xanh · Giá mặc định website","enabled":true,"pricing_scope":"chain","pricing_context":{"provinceId":1027},"ready":true,"reason":null},
        {"id":5,"retailer":"lottemart","external_id":"nsg","name":"LOTTE Mart","enabled":true,"pricing_scope":"chain","pricing_context":null,"ready":true,"reason":null},
        {"id":9,"retailer":"winmart","external_id":"off","name":"Disabled","enabled":false,"pricing_scope":"chain","pricing_context":null,"ready":true,"reason":null}]"""
    private val categoriesByStore = mapOf(
        "1" to """[{"id":8,"store_id":1,"external_id":"8","name":"RAU CỦ","locator":"https://sala.emartmall.com.vn/index.php?route=product/category&path=8","source_details":null,"last_success":"2026-11-05T10:32:16+07:00","stats":{}},
                   {"id":10,"store_id":1,"external_id":"10","name":"Rau lá","locator":"https://sala.emartmall.com.vn/index.php?route=product/category&path=10","source_details":null,"last_success":null,"stats":{}}]""",
        "4" to """[{"id":325,"store_id":4,"external_id":"8686","name":"Thịt, cá, trứng, hải sản","locator":"https://www.bachhoaxanh.com/thit-ca-trung-rau-cu","source_details":{"kind":"GroupGrandFather","parents":[]},"stats":{}},
                   {"id":326,"store_id":4,"external_id":"8781","name":"Thịt heo","locator":"https://www.bachhoaxanh.com/thit-heo","source_details":{"kind":"GroupV2","parents":["8686"]},"stats":{}}]""",
        "5" to """[{"id":491,"store_id":5,"external_id":"3","name":"Rau Củ","locator":"https://www.lottemart.vn/vi-nsg/category/rau-cu-c3","source_details":{"kind":"goods","parents":[]},"stats":{}}]""",
    )
    private val emart = """{"id":1,"retailer":"emart","pricing_scope":"branch","external_id":"34732","name":"Đọt Rau Lang Organic Vinamit 300G","updated_at":"2026-11-05T10:03:28.660769+07:00",
        "offers":[{"id":1,"store_id":1,"category_id":10,"variant":"default","source_url":"https://sala.emartmall.com.vn/index.php?route=product/product&product_id=34732",
        "image_url":"https://emartmall.com.vn/image/cache/catalog/products/8934743032375/8934743032375_11-600x600.jpg","category_ids":[8,10,16],"price":44800,"reference_price":null,
        "details":{"availability":"unknown","currency":"VND","on_sale":false,"conditions":[],"source_mode":"live","campaign_candidates":[]},"updated_at":"2026-11-05T10:03:28.660769+07:00"}]}"""
    private val bhx = """{"id":26808,"retailer":"bhx","pricing_scope":"chain","external_id":"226834","name":"Ba rọi heo C.P","updated_at":"2026-11-09T16:32:45.063380+07:00",
        "offers":[{"id":27042,"store_id":4,"category_id":325,"variant":"weight:0.3kg","source_url":"https://www.bachhoaxanh.com/thit-heo/ba-roi-heo-1kg","image_url":"https://cdnv2.tgdd.vn/bhx/ba-roi.jpg",
        "category_ids":[325,326],"price":44700,"reference_price":46200,"details":{"currency":"VND","availability":"available","variant_label":"0.3 kg","conditions":[],"on_sale":true,"requires_detail":false,
        "source_price":{"price":44700.0,"sysPrice":46200.0,"storeId":2546},"pricing_context":{"provinceId":1027,"storeId":2546}},"updated_at":"2026-11-09T16:32:45.063380+07:00"},
        {"id":27043,"store_id":4,"category_id":325,"variant":"weight:0.5kg","source_url":"https://www.bachhoaxanh.com/thit-heo/ba-roi-heo-1kg","image_url":null,"category_ids":[325,326],
        "price":74500.0,"reference_price":77000,"details":{"currency":"VND","availability":"available","variant_label":"0.5 kg","conditions":[{"type":"quantity","min":2}],"on_sale":true},"updated_at":"2026-11-09T16:32:45.063380+07:00"}]}"""
    private val lotte = """{"id":33535,"retailer":"lottemart","pricing_scope":"chain","external_id":"0412133820002","name":"Củ Sắn (Củ Đậu) Tươi 1Kg (ea)","updated_at":"2026-11-09T09:48:55.436974+07:00",
        "offers":[{"id":34064,"store_id":5,"category_id":491,"variant":"default","source_url":"https://www.lottemart.vn/vi-nsg/product/cu-san-tuoi-1kg-0412133820002-p228920",
        "image_url":"https://www.lottemart.vn/media/catalog/product/cache/300x300/0/4/0400271040000-1.jpg.webp","category_ids":[491,496],"price":29500,"reference_price":null,
        "details":{"availability":"available","variant_label":"EA","source_product_id":"228920","currency":"VND","on_sale":false},"updated_at":"2026-11-09T09:48:55.436974+07:00"}]}"""
    private fun page(items: List<String>, total: Long, limit: Int = 100, offset: Long = 0) =
        """{"total":$total,"items":[${items.joinToString(",")}],"limit":$limit,"offset":$offset,"mode":"live"}"""

    /** Serves stores/categories and a product source computed from `offset`/`limit`, like CrawlData. */
    private inner class Fixture(var products: (offset: Long, limit: Int, query: Map<String, String>) -> String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        var productStatus = 200
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
                }
                requests += path + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
                val (status, body) = when (path) {
                    "/api/v1/stores" -> 200 to stores
                    "/api/v1/categories" -> categoriesByStore[query["store_id"]]?.let { 200 to it } ?: (404 to """{"detail":"Not Found"}""")
                    "/api/v1/products" -> productStatus to products(query.getValue("offset").toLong(), query.getValue("limit").toInt(), query)
                    else -> 404 to """{"detail":"Not Found"}"""
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            this.executor = this@Fixture.executor
            start()
        }
        val origin get() = "http://127.0.0.1:${server.address.port}"
        fun client() = CrawlDataClient(CrawlDataConfig.create(origin, Duration.ofSeconds(5)), clock)
        override fun close() { server.stop(0); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun configurationAllowsHttpOnlyOnLoopbackAndNeedsNoKey() {
        assertEquals(CrawlDataConfig.DEFAULT_ORIGIN, CrawlDataConfig.fromEnvironment(emptyMap()).origin.toString())
        assertEquals("http://127.0.0.1:8000", CrawlDataConfig.fromEnvironment(mapOf("CRAWLDATA_BASE_URL" to "http://127.0.0.1:8000/")).origin.toString())
        listOf("http://localhost:8000", "http://LOCALHOST:8000", "http://[::1]:8000", "https://crawl.example.test").forEach { CrawlDataConfig.create(it) }
        listOf("http://192.168.1.5:8000", "http://crawl.example.test", "http://10.0.2.2:8000", "ftp://127.0.0.1", "http://u:p@127.0.0.1:8000",
            "http://127.0.0.1:8000/api", "http://127.0.0.1:8000/?x=1", "http://127.0.0.1:8000/#x", "not a url").forEach {
            assertFailsWith<IllegalArgumentException>(it) { CrawlDataConfig.create(it) }
        }
    }

    @Test fun providerSelectionDefaultsToCatalogFlow() {
        assertEquals(CatalogSource.CATALOGFLOW, CatalogProviders.source(emptyMap()))
        assertEquals(CatalogSource.CATALOGFLOW, CatalogProviders.source(mapOf("CATALOG_PROVIDER" to " CatalogFlow ")))
        assertEquals(CatalogSource.CRAWLDATA, CatalogProviders.source(mapOf("CATALOG_PROVIDER" to "crawldata")))
        assertFailsWith<IllegalArgumentException> { CatalogProviders.source(mapOf("CATALOG_PROVIDER" to "dicho")) }
        // Unchanged default: no CatalogFlow key means no catalog, and CrawlData variables are ignored.
        assertNull(CatalogProviders.fromEnvironment(mapOf("CRAWLDATA_BASE_URL" to "http://127.0.0.1:8000")))
        CatalogProviders.fromEnvironment(mapOf("CATALOGFLOW_API_KEY" to "fixture-key")).use {
            assertEquals(CatalogSource.CATALOGFLOW, it!!.source); assertTrue(it.provider is IncrementalCatalogProvider)
        }
        CatalogProviders.fromEnvironment(mapOf("CATALOG_PROVIDER" to "crawldata", "CATALOGFLOW_API_KEY" to "ignored")).use {
            assertEquals(CatalogSource.CRAWLDATA, it!!.source)
            assertFalse(it.provider is IncrementalCatalogProvider, "CrawlData uses full paged import")
            assertTrue(it.provider is CrawlDataClient)
        }
        assertFailsWith<IllegalArgumentException> { CatalogProviders.fromEnvironment(mapOf("CATALOG_PROVIDER" to "crawldata", "CRAWLDATA_BASE_URL" to "http://192.168.1.5:8000")) }
    }

    @Test fun everyOfferBecomesOneProviderProduct() = runBlocking {
        Fixture { offset, limit, _ -> page(listOf(emart, bhx, lotte), 3, limit, offset) }.use { fixture ->
            fixture.client().use { client ->
                val result = client.products(CatalogQuery(limit = 100))
                assertEquals(3L, result.total); assertEquals(3, result.upstreamCount, "pagination counts products")
                assertEquals(listOf("offer-1", "offer-27042", "offer-27043", "offer-34064"), result.items.map { it.id })
                assertEquals(0, result.rejectedItems); assertTrue(result.warnings.isEmpty())

                val branch = result.items[0]
                assertEquals("emart", branch.website); assertEquals("1", branch.upstreamProductId)
                assertEquals(BigDecimal(44800), branch.price); assertEquals("VND", branch.currency)
                assertEquals("sản phẩm", branch.unit); assertNull(branch.quantityLabel)
                assertTrue(branch.hasStoreMetadata, "Emart branch price is store-scoped")
                assertEquals(listOf("10", "8"), branch.categories.map { it.id }, "leaf category first, known names only")
                assertEquals("Rau lá", branch.categories.first().name)
                assertTrue("UNKNOWN_CATEGORY" in branch.warnings, "category 16 has no name upstream")
                assertEquals(BigDecimal("1793847808.660769"), branch.observedAt)
                assertEquals(false, branch.stale)

                val weight = result.items[1]
                assertEquals("0.3 kg", weight.unit); assertEquals("0.3 kg", weight.quantityLabel)
                assertEquals(BigDecimal(46200), weight.originalPrice); assertEquals(BigDecimal(44700), weight.salePrice)
                assertFalse(weight.hasStoreMetadata); assertEquals(listOf("325", "326"), weight.categories.map { it.id })
                assertEquals("325", weight.categories[1].parentId, "parent resolved from source_details.parents")
                assertEquals("https://www.bachhoaxanh.com/thit-heo/ba-roi-heo-1kg", weight.url)
                assertTrue(weight.warnings.isEmpty())

                val conditional = result.items[2]
                assertNull(conditional.price, "conditional price is not a shelf price")
                assertTrue("PRICE_CONDITIONS_REQUIRE_REVIEW" in conditional.warnings)
                assertTrue(conditional.images.isEmpty())

                assertEquals(listOf("491"), result.items[3].categories.map { it.id })
            }
            val stores = fixture.requests.filter { it.startsWith("/api/v1/categories") }
            assertEquals(listOf("store_id=1", "store_id=4", "store_id=5"), stores.map { it.substringAfter('?') }, "disabled store skipped")
        }
    }

    @Test fun queryUsesOffsetPaginationAndReloadsCategoriesPerRun() = runBlocking {
        Fixture { offset, limit, _ -> page(emptyList(), 120, limit, offset) }.use { fixture ->
            fixture.client().use { client ->
                client.products(CatalogQuery(page = 1, limit = 50, site = "lottemart", search = "sữa", category = "491"))
                client.products(CatalogQuery(page = 3, limit = 50))
                client.products(CatalogQuery(page = 1, limit = 50))
            }
            val products = fixture.requests.filter { it.startsWith("/api/v1/products") }
            assertEquals("/api/v1/products?limit=50&offset=0&retailer=lottemart&q=s%E1%BB%AFa&category_id=491", products[0])
            assertEquals("/api/v1/products?limit=50&offset=100", products[1])
            assertEquals(2, fixture.requests.count { it == "/api/v1/stores" }, "categories reload only at page 1")
        }
    }

    @Test fun malformedResponsesFailSafely() = runBlocking {
        val cases = listOf(
            """{"total":1,"items":[],"limit":100,"offset":100,"mode":"live"}""", // wrong offset echo
            """{"total":1,"items":[],"limit":10,"offset":0}""",                // wrong limit echo
            """{"items":[]}""", "[]", "not json")
        for (body in cases) Fixture { _, _, _ -> body }.use { fixture ->
            fixture.client().use { client ->
                val failure = assertFailsWith<ProviderFailure>(body) { client.products(CatalogQuery(limit = 100)) }
                assertEquals(ProviderFailureCode.INVALID_RESPONSE, failure.code)
            }
        }
        Fixture { _, _, _ -> """{"detail":[{"msg":"Input should be less than or equal to 100"}]}""" }.use { fixture ->
            fixture.productStatus = 422
            fixture.client().use { assertEquals(ProviderFailureCode.INVALID_RESPONSE, assertFailsWith<ProviderFailure> { it.products() }.code) }
        }
        // Invalid offers are counted as rejected; the rest of the page is still decoded.
        val broken = """{"id":7,"retailer":"bhx","pricing_scope":"chain","name":"X","updated_at":"2026-11-09T09:48:55+07:00","offers":[{"store_id":4},{"id":70,"price":"12000","category_ids":[325],"details":{"currency":"VND"}}]}"""
        Fixture { offset, limit, _ -> page(listOf(broken, """{"id":8}"""), 2, limit, offset) }.use { fixture ->
            fixture.client().use { client ->
                val result = client.products(CatalogQuery(limit = 100))
                assertEquals(2, result.rejectedItems); assertEquals(listOf("offer-70"), result.items.map { it.id })
                assertNull(result.items[0].price, "string price is never coerced"); assertTrue("INVALID_PRICE" in result.items[0].warnings)
                assertTrue(result.partial)
            }
        }
    }

    @Test fun normalizerKeepsChainPricesAndHidesBranchOrConditionalPrices() = runBlocking {
        Fixture { offset, limit, _ -> page(listOf(emart, bhx, lotte), 3, limit, offset) }.use { fixture ->
            val items = fixture.client().use { it.products(CatalogQuery(limit = 100)).items }
            val normalizer = CatalogNormalizer(clock)
            val records = items.map { assertNotNull(normalizer.normalize(it), it.id) }
            assertNull(records[0].offer.price); assertTrue(records[0].offer.warnings.any { it.code == "STORE_PRICE_SCOPE_UNVERIFIED" })
            assertEquals("44700", records[1].offer.price); assertEquals("Bách Hóa Xanh", records[1].retailer.name)
            assertNull(records[2].offer.price)
            assertEquals("29500", records[3].offer.price); assertEquals("Lotte Mart", records[3].retailer.name)
            assertEquals("0.3 kg", records[1].product.package_label)
        }
    }

    /** Needs the isolated PostgreSQL test cluster (127.0.0.1:55432), like every other database-backed backend test. */
    @Test fun fullPagedImportCountsProductsNotOffers() = runBlocking {
        // 250 products; every third (83) has two offers. Offer count (333) exceeds total (250), which used to end paging early.
        val products = (1..250).map { i ->
            val offers = (1..(if (i % 3 == 0) 2 else 1)).joinToString(",") { v ->
                """{"id":${i * 10 + v},"store_id":5,"category_id":491,"category_ids":[491],"price":${1000 * i + v},"reference_price":null,
                    "source_url":"https://www.lottemart.vn/p$i-$v","image_url":null,"details":{"currency":"VND","variant_label":"Gói $v"},"updated_at":"2026-11-09T09:48:55+07:00"}"""
            }
            """{"id":$i,"retailer":"lottemart","pricing_scope":"chain","name":"Sản phẩm $i","updated_at":"2026-11-09T09:48:55+07:00","offers":[$offers]}"""
        }
        val db = Database("jdbc:postgresql://127.0.0.1:55432/auth00_test", "auth00_test", "")
        try {
            db.migrate(); resetAuthTestData(db)
            Fixture { offset, limit, _ -> page(products.drop(offset.toInt()).take(limit), products.size.toLong(), limit, offset) }.use { fixture ->
                val result = fixture.client().use { CatalogImporter(db, it, clock).sync(maxPages = CatalogSource.CRAWLDATA.maxSyncPages) }
                assertEquals(333, result.received); assertEquals(333, result.imported); assertEquals(0, result.rejected)
                assertFalse(result.partial); assertEquals(1, result.sources)
                assertEquals(3, fixture.requests.count { it.startsWith("/api/v1/products") })
                val (active, priced) = db.transaction { c -> query(c, "SELECT count(*) FILTER (WHERE e.active), count(*) FILTER (WHERE o.payload->>'price' IS NOT NULL) FROM catalog_entries e JOIN shopping_offers o ON o.id=e.offer_id").use { s ->
                    s.executeQuery().use { r -> r.next(); r.getInt(1) to r.getInt(2) } } }
                assertEquals(333, active); assertEquals(333, priced)
            }
        } finally { resetAuthTestData(db); db.close() }
    }

}
