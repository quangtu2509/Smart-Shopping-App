package com.toked.smartshopping.backend.catalog

import com.toked.smartshopping.backend.shopping.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale

internal data class CatalogReference(val id: String, val name: String)
internal data class CatalogRecord(val retailer: CatalogReference, val site: String, val categories: List<CatalogReference>,
    val brand: CatalogReference?, val product: Product, val offer: Offer)

/** A provider listing is one package/count unit. No cross-retailer product matching or unit-price inference. */
internal class CatalogNormalizer(private val clock: Clock) {
    fun normalize(item: ProviderProduct): CatalogRecord? {
        if (item.currency != "VND") return null
        val unit = item.unit?.trim()?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val categories = item.categories.mapNotNull { category ->
            category.name?.trim()?.takeIf { it.isNotBlank() }?.let { CatalogReference(id("category", item.website, category.id), it.take(300)) }
        }.distinctBy { it.id }
        if (categories.isEmpty()) return null
        val retailer = CatalogReference(retailerId(item.website), retailerName(item.website))
        val brand = item.brand?.trim()?.takeIf { it.isNotBlank() }?.let { CatalogReference(id("brand", it.lowercase(Locale.ROOT)), it.take(300)) }
        val productId = id("product", item.website, item.id)
        val product = Product(productId, item.name.take(1000), categories.first().id, unit, "1", brand?.id,
            image_url = item.images.firstOrNull(), brand_name=brand?.name, package_label=item.quantityLabel?.take(300))
        val warnings = mutableListOf<ShoppingWarning>()
        fun warn(code: String, message: String) { warnings += ShoppingWarning(code, message) }
        val observed = item.observedAt?.let(::timestamp)
        val source = item.url ?: item.source?.takeIf { it.isNotBlank() }
        var price = money(item.price)
        if (source == null || observed == null) {
            price = null
            warn("PRICE_METADATA_MISSING", "Chưa đủ nguồn hoặc thời điểm quan sát để xác nhận giá.")
        }
        if (price == null) warn("PRICE_UNKNOWN", "Chưa có giá hợp lệ cho sản phẩm này.")
        if (item.priceOptions.size > 1 || item.variantCount > 1) {
            // Upstream does not specify which option/condition is universally applicable. Keep demand unpriced.
            price = null
            warn("PRICE_OPTIONS_REQUIRE_REVIEW", "Có nhiều quy cách hoặc điều kiện giá; chưa xác nhận mức giá áp dụng.")
        }
        if(item.hasStoreMetadata) {
            price=null
            warn("STORE_PRICE_SCOPE_UNVERIFIED","Nguồn có dữ liệu theo cửa hàng; chưa xác minh chi nhánh và phạm vi áp dụng giá.")
        }
        warn("LISTING_QUANTITY_STEP", "Đơn vị đếm hiện là một đơn vị niêm yết; chưa hỗ trợ chia nhỏ quy cách.")
        val freshness = when {
            observed == null || item.stale == null -> Freshness.UNKNOWN
            item.stale || observed.isBefore(clock.instant().minusSeconds(86400)) -> Freshness.STALE
            else -> Freshness.FRESH
        }
        val offer = Offer(OfferRef(OfferKind.CATALOG, id("offer", item.website, item.id)), "pending", product,
            retailer_id = retailer.id, price = price, currency = "VND", source = source, observed_at = observed?.toString(),
            price_scope = PriceScope.RETAILER, freshness = freshness, stock_status = StockStatus.UNKNOWN,
            list_price = money(item.originalPrice), warnings = warnings)
        val version = id("price2", Json.encodeToString(offer))
        return CatalogRecord(retailer, item.website, categories, brand, product, offer.copy(version = version))
    }
    private fun money(value: BigDecimal?): String? = value?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 && it.precision() <= 30 }
        ?.setScale(0)?.toPlainString()
    private fun timestamp(value: BigDecimal): Instant? = try {
        // CatalogFlow public UI uses Unix seconds. Millisecond/invalid/future epochs are not guessed.
        val seconds = value.setScale(0,java.math.RoundingMode.DOWN).longValueExact()
        val nanos = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).toInt()
        Instant.ofEpochSecond(seconds, nanos.toLong()).takeIf { it >= Instant.parse("2000-01-01T00:00:00Z") && it <= clock.instant().plusSeconds(300) }
    } catch (_: Exception) { null }
    companion object {
        fun retailerId(site: String) = id("retailer", site)
        fun id(kind: String, vararg values: String): String = "cf_${kind}_" + MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
        fun retailerName(site: String): String = when {
            site.contains("bachhoaxanh", true) -> "Bách Hóa Xanh"
            site.contains("coop", true) -> "Co.opmart"
            site.contains("kingfood", true) -> "Kingfoodmart"
            site.contains("emart", true) -> "Emart"
            site.contains("lotte", true) -> "Lotte Mart"
            else -> site.take(200)
        }
    }
}
