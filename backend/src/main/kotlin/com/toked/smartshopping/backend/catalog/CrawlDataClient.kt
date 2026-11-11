package com.toked.smartshopping.backend.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Adapter for the self-hosted CrawlData service (`GET /api/v1/products`, offset pagination, one product with
 * one or more offers). Every offer becomes one [ProviderProduct]. Full paged import only: CrawlData has no
 * snapshot/changes protocol yet, so this deliberately does not implement [IncrementalCatalogProvider].
 */
class CrawlDataClient(private val config: CrawlDataConfig, clock: Clock = Clock.systemUTC()) : CatalogProvider, AutoCloseable {
    private val http = ProviderHttp(config.timeout, config.maxResponseBytes, clock)
    private val decoder = CrawlDataDecoder()
    private val categoryLock = Mutex()
    @Volatile private var categories: Map<String, ProviderCategory>? = null

    override suspend fun products(query: CatalogQuery): ProviderPage {
        // Category names live per store. Reload at the start of each paged run so names follow upstream changes.
        val names = categories(reload = query.page == 1)
        val params = linkedMapOf("limit" to query.limit.toString(), "offset" to ((query.page - 1).toLong() * query.limit).toString())
        query.site?.takeIf { it.isNotBlank() }?.let { params["retailer"] = it }
        query.search?.takeIf { it.isNotBlank() }?.let { params["q"] = it }
        query.category?.takeIf { it.isNotBlank() }?.let { params["category_id"] = it }
        return decoder.page(http.get(config.origin, "/api/v1/products", params), query, names)
    }

    override suspend fun branches(): List<ProviderBranch> = decoder.branches(http.get(config.origin, "/api/v1/stores", emptyMap()))

    private suspend fun categories(reload: Boolean): Map<String, ProviderCategory> = categoryLock.withLock {
        categories?.takeUnless { reload } ?: run {
            val stores = decoder.storeIds(http.get(config.origin, "/api/v1/stores", emptyMap()))
            val raw = stores.flatMap { decoder.categories(http.get(config.origin, "/api/v1/categories", mapOf("store_id" to it.toString()))) }
            decoder.resolveCategories(raw).also { categories = it }
        }
    }

    override fun close() = http.close()
}

internal data class CrawlCategory(val id: String, val storeId: String, val externalId: String?, val name: String,
    val url: String?, val parentExternalIds: List<String>)

/** Keeps only fields Smart Shopping uses; raw source payloads (`source_price`, pricing context) are never imported. */
internal class CrawlDataDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    private val retailerPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    fun storeIds(body: String): List<String> {
        val stores = parse(body) as? JsonArray ?: invalid()
        if (stores.size > 1000) invalid()
        return stores.mapNotNull { element ->
            val store = element as? JsonObject ?: invalid()
            val id = identifier(store["id"]) ?: invalid()
            id.takeIf { boolean(store["enabled"]) != false && boolean(store["ready"]) != false }
        }.distinct()
    }

    /** Enabled, ready stores priced per branch (e.g. Emart). Chain-priced stores are website defaults, not branches. */
    fun branches(body: String): List<ProviderBranch> {
        val stores = parse(body) as? JsonArray ?: invalid()
        if (stores.size > 1000) invalid()
        return stores.mapNotNull { element ->
            val store = element as? JsonObject ?: invalid()
            if (text(store["pricing_scope"]) != "branch" || boolean(store["enabled"]) == false || boolean(store["ready"]) == false) return@mapNotNull null
            val retailer = text(store["retailer"])?.takeIf { it.matches(retailerPattern) } ?: return@mapNotNull null
            val external = identifier(store["external_id"]) ?: return@mapNotNull null
            val name = text(store["name"])?.trim()?.takeIf { it.isNotBlank() }?.take(300) ?: return@mapNotNull null
            ProviderBranch(retailer, external, name)
        }.distinctBy { it.website to it.externalId }
    }

    fun categories(body: String): List<CrawlCategory> {
        val items = parse(body) as? JsonArray ?: invalid()
        if (items.size > 20_000) invalid()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = identifier(item["id"]) ?: return@mapNotNull null
            val name = text(item["name"])?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val parents = ((item["source_details"] as? JsonObject)?.get("parents") as? JsonArray).orEmpty().mapNotNull(::identifier)
            CrawlCategory(id, identifier(item["store_id"]) ?: "", identifier(item["external_id"]), name, safeUrl(text(item["locator"])), parents)
        }
    }

    /** The direct parent is the last listed ancestor external id within the same store. */
    fun resolveCategories(raw: List<CrawlCategory>): Map<String, ProviderCategory> {
        val byExternal = raw.filter { it.externalId != null }.associateBy { it.storeId to it.externalId }
        return raw.associate { category ->
            val parent = category.parentExternalIds.lastOrNull()?.let { byExternal[category.storeId to it]?.id }
            category.id to ProviderCategory(category.id, category.name, category.url, parent)
        }
    }

    fun page(body: String, query: CatalogQuery, categories: Map<String, ProviderCategory>): ProviderPage {
        val root = parse(body) as? JsonObject ?: invalid()
        val items = root["items"] as? JsonArray ?: invalid()
        val total = integer(root["total"]) ?: invalid()
        val expectedOffset = (query.page - 1).toLong() * query.limit
        if (integer(root["limit"]) != query.limit.toLong() || integer(root["offset"]) != expectedOffset ||
            total < 0 || items.size > query.limit) invalid()
        var rejected = 0
        val offers = mutableListOf<ProviderProduct>()
        for (element in items) {
            val product = element as? JsonObject
            val rawOffers = product?.get("offers") as? JsonArray
            if (product == null || rawOffers == null) { rejected++; continue }
            for (offer in rawOffers) {
                val decoded = (offer as? JsonObject)?.let { offer(product, it, categories) }
                if (decoded == null) rejected++ else offers += decoded
            }
        }
        val unique = offers.distinctBy { it.id }
        val warnings = buildList {
            if (rejected > 0) add("INVALID_PROVIDER_ITEMS")
            if (unique.size != offers.size) add("DUPLICATE_PROVIDER_ITEMS")
        }
        // Pagination counts upstream products, not the offers expanded from them.
        return ProviderPage(unique, total, query.page, query.limit, rejected, warnings, upstreamCount = items.size)
    }

    private fun offer(product: JsonObject, offer: JsonObject, categories: Map<String, ProviderCategory>): ProviderProduct? {
        val productId = identifier(product["id"]) ?: return null
        val offerId = identifier(offer["id"]) ?: return null
        val name = text(product["name"])?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val retailer = text(product["retailer"])?.takeIf { it.matches(retailerPattern) } ?: return null
        val details = offer["details"] as? JsonObject ?: JsonObject(emptyMap())
        val warnings = mutableListOf<String>()

        var price = decimal(offer["price"])
        if (offer["price"] != null && offer["price"] != JsonNull && price == null) warnings += "INVALID_PRICE"
        if (price == null) warnings += "MISSING_PRICE"
        val conditional = (details["conditions"] as? JsonArray)?.isNotEmpty() == true || boolean(details["requires_detail"]) == true
        if (conditional && price != null) {
            // Price depends on a quantity/campaign condition CrawlData does not resolve. Never treat it as the shelf price.
            price = null
            warnings += "PRICE_CONDITIONS_REQUIRE_REVIEW"
        }
        val currency = text(details["currency"])?.trim()?.takeIf { it.isNotBlank() }
        if (currency == null) warnings += "MISSING_CURRENCY"
        val observed = epoch(offer["updated_at"]) ?: epoch(product["updated_at"])
        if (observed == null) warnings += "MISSING_OBSERVED_AT"

        val categoryIds = (listOfNotNull(identifier(offer["category_id"])) +
            (offer["category_ids"] as? JsonArray).orEmpty().mapNotNull(::identifier)).distinct().take(100)
        val named = categoryIds.mapNotNull { categories[it] }
        if (named.size != categoryIds.size) warnings += "UNKNOWN_CATEGORY"

        val variantLabel = text(details["variant_label"])?.trim()?.takeIf { it.isNotBlank() }?.take(200)
        val sourceUrl = safeUrl(text(offer["source_url"])) ?: safeUrl(text(details["source_url"]))
        val image = safeUrl(text(offer["image_url"])) ?: safeUrl(text(details["image_url"]))
        return ProviderProduct(
            id = "offer-$offerId", upstreamProductId = productId, name = name.take(1000), website = retailer,
            source = "crawldata", url = sourceUrl, images = listOfNotNull(image), brand = null,
            sku = text(details["source_sku"])?.take(200),
            // One offer is one listed unit/package. CrawlData publishes no separate measuring unit.
            unit = variantLabel ?: "sản phẩm", quantityLabel = variantLabel, currency = currency,
            price = price, apiPrice = null, originalPrice = decimal(offer["reference_price"]),
            salePrice = if (boolean(details["on_sale"]) == true) price else null,
            priceOptions = emptyList(), variantCount = 1, availability = text(details["availability"])?.take(50),
            observedAt = observed, lastUpdatedAt = epoch(product["updated_at"]), lastSuccessAt = null,
            // CrawlData has no stale flag; the normalizer derives freshness from the observation time.
            stale = if (observed == null) null else false,
            categories = named,
            // Branch-scoped prices (e.g. per Emart store) are not verified as retailer-wide shelf prices.
            hasStoreMetadata = text(product["pricing_scope"]) != "chain",
            warnings = warnings.distinct(),
        )
    }

    private fun parse(body: String): JsonElement = try { json.parseToJsonElement(body) }
        catch (_: SerializationException) { invalid() } catch (_: IllegalArgumentException) { invalid() }
    private fun epoch(value: JsonElement?): BigDecimal? {
        val raw = text(value) ?: return null
        val instant = try { OffsetDateTime.parse(raw).toInstant() } catch (_: DateTimeParseException) { return null }
        return BigDecimal.valueOf(instant.epochSecond).add(BigDecimal.valueOf(instant.nano.toLong(), 9)).stripTrailingZeros()
            .let { if (it.scale() < 0) it.setScale(0) else it }
    }
    private fun integer(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun boolean(value: JsonElement?): Boolean? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(4000)
    private fun identifier(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf {
        it != JsonNull && (it.isString || it.longOrNull != null)
    }?.content?.takeIf { it.isNotBlank() && it.length <= 200 }
    private fun decimal(value: JsonElement?): BigDecimal? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive == JsonNull || primitive.isString) return null
        val content = primitive.content
        if (content.length > 100 || !content.matches(Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,2})?"))) return null
        return content.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.precision() <= 38 && it.scale() in -18..18 }
    }
    private fun safeUrl(value: String?): String? {
        if (value == null) return null
        val uri = try { URI(value) } catch (_: Exception) { return null }
        return value.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }
    private fun invalid(): Nothing = throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE)
}
