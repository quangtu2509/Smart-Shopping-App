package com.toked.smartshopping.backend.catalog

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI

/** Deliberately excludes raw retailer payload/evidence, credentials and arbitrary upstream fields. */
internal class CatalogFlowDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun page(body: String, query: CatalogQuery): ProviderPage {
        val root = try { json.parseToJsonElement(body) as? JsonObject ?: invalid() }
            catch (_: SerializationException) { invalid() }
            catch (_: IllegalArgumentException) { invalid() }
        val items = root["items"] as? JsonArray ?: invalid()
        val total = integer(root["total"]) ?: invalid()
        if (integer(root["page"]) != query.page.toLong() || integer(root["limit"]) != query.limit.toLong() ||
            total < items.size || items.size > query.limit || total < 0) invalid()
        var rejected = 0
        val decoded = items.mapNotNull { element ->
            val obj = element as? JsonObject
            if (obj == null) { rejected++; null }
            else product(obj).also { if (it == null) rejected++ }
        }
        val unique = decoded.distinctBy { it.website to it.id }
        val warnings = buildList {
            if (rejected > 0) add("INVALID_PROVIDER_ITEMS")
            if (unique.size != decoded.size) add("DUPLICATE_PROVIDER_ITEMS")
        }
        return ProviderPage(unique, total, query.page, query.limit, rejected, warnings)
    }

    private fun product(obj: JsonObject): ProviderProduct? {
        val id = identifier(obj["id"]) ?: return null
        val name = text(obj["name"])?.takeIf { it.isNotBlank() } ?: return null
        val website = text(obj["website"])?.takeIf { it.isNotBlank() } ?: return null
        // A private-key API must never smuggle an unapproved record into the application.
        if (boolean(obj["approved"]) != true) return null
        val warnings = mutableListOf<String>()
        fun amount(field: String): BigDecimal? = decimal(obj[field]).also {
            if (obj[field] != null && obj[field] != JsonNull && it == null) warnings += "INVALID_${field.uppercase()}"
        }
        val currency = text(obj["currency"])
        if (currency.isNullOrBlank()) warnings += "MISSING_CURRENCY"
        if (text(obj["source"]).isNullOrBlank()) warnings += "MISSING_SOURCE"
        val price = amount("price")
        if (price == null) warnings += "MISSING_PRICE"
        val observed = decimal(obj["observed_at"])
        if (observed == null) warnings += "MISSING_OBSERVED_AT"
        val stale = boolean(obj["stale"])
        if (stale == null) warnings += "MISSING_FRESHNESS"
        val images = (obj["images"] as? JsonArray).orEmpty().mapNotNull { safeUrl(text(it)) }.distinct().take(20)
        val options = (obj["price_options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val item = option as? JsonObject ?: return@mapNotNull null
            ProviderPriceOption(decimal(item["price"]), text(item["quantity_label"]))
        }.take(100)
        val categories = (obj["categories"] as? JsonArray).orEmpty().mapNotNull { category ->
            val item = category as? JsonObject ?: return@mapNotNull null
            ProviderCategory(identifier(item["id"]) ?: return@mapNotNull null, text(item["name"]), safeUrl(text(item["url"])), identifier(item["parent_id"]))
        }.distinctBy { it.id }.take(100)
        return ProviderProduct(id, identifier(obj["product_id"]), name, website, text(obj["source"]), safeUrl(text(obj["url"])),
            images, text(obj["brand"]), text(obj["sku"]), text(obj["unit"]), text(obj["quantity_label"]), currency, price,
            amount("api_price"), amount("original_price"), amount("sale_price"), options, (obj["variants"] as? JsonArray)?.size ?: 0,
            text(obj["availability"]), observed, decimal(obj["last_updated_at"]), decimal(obj["last_success_at"]), stale,
            categories, (obj["stores"] as? JsonArray)?.isNotEmpty() == true, warnings.distinct())
    }

    private fun integer(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun boolean(value: JsonElement?): Boolean? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(4000)
    private fun identifier(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf {
        it != JsonNull && (it.isString || it.longOrNull != null)
    }?.content?.takeIf { it.isNotBlank() && it.length <= 200 }
    private fun decimal(value: JsonElement?): BigDecimal? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive == JsonNull) return null
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
