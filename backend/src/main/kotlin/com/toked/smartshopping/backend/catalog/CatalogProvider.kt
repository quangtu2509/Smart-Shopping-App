package com.toked.smartshopping.backend.catalog

import java.math.BigDecimal

/** Backend-only boundary. These records are evidence from the provider, not Smart Shopping Offers. */
interface CatalogProvider {
    suspend fun products(query: CatalogQuery = CatalogQuery()): ProviderPage
}

data class CatalogQuery(
    val page: Int = 1, val limit: Int = 50, val search: String? = null, val site: String? = null,
    val collection: String? = null, val category: String? = null,
) {
    init {
        require(page in 1..1_000_000 && limit in 1..100) { "Invalid provider pagination" }
        require(listOf(search, site, collection, category).all { it == null || (it.length <= 2000 && it.none(Char::isISOControl)) }) {
            "Invalid provider filter"
        }
    }
}

data class ProviderCategory(val id: String, val name: String?, val url: String?, val parentId: String?)
data class ProviderPriceOption(val price: BigDecimal?, val quantityLabel: String?)
data class ProviderProduct(
    val id: String, val upstreamProductId: String?, val name: String, val website: String,
    val source: String?, val url: String?, val images: List<String>, val brand: String?, val sku: String?,
    val unit: String?, val quantityLabel: String?, val currency: String?, val price: BigDecimal?,
    val apiPrice: BigDecimal?, val originalPrice: BigDecimal?, val salePrice: BigDecimal?,
    val priceOptions: List<ProviderPriceOption>, val variantCount: Int, val availability: String?,
    // Preserve numeric timestamp evidence; converting seconds/milliseconds and freshness policy belongs to PRICE-01.
    val observedAt: BigDecimal?, val lastUpdatedAt: BigDecimal?, val lastSuccessAt: BigDecimal?,
    val stale: Boolean?, val categories: List<ProviderCategory>, val hasStoreMetadata: Boolean,
    val warnings: List<String>,
)
data class ProviderPage(
    val items: List<ProviderProduct>, val total: Long, val page: Int, val limit: Int,
    val rejectedItems: Int, val warnings: List<String>,
) {
    val partial: Boolean get() = rejectedItems > 0 || warnings.isNotEmpty() || items.any { it.warnings.isNotEmpty() }
}

enum class ProviderFailureCode {
    UNCONFIGURED, AUTHENTICATION, FORBIDDEN, RATE_LIMITED, UNAVAILABLE, REDIRECT,
    TIMEOUT, NETWORK, INVALID_RESPONSE, RESPONSE_TOO_LARGE,
}

/** Never attach an upstream exception/body/URL/header as a message or cause. */
class ProviderFailure(
    val code: ProviderFailureCode, val retryable: Boolean = false,
    val retryAfterSeconds: Int? = null, val upstreamStatus: Int? = null,
) : RuntimeException("Catalog provider: ${code.name}")
