package com.toked.smartshopping.backend.catalog

import java.time.Clock

/** One application-scoped HTTP client. No cache, retries, background crawler or database work. */
class CatalogFlowClient(private val config: CatalogFlowConfig, clock: Clock = Clock.systemUTC()) : IncrementalCatalogProvider, AutoCloseable {
    private val http = ProviderHttp(config.timeout, config.maxResponseBytes, clock)
    private val decoder = CatalogFlowDecoder()

    override suspend fun products(query: CatalogQuery): ProviderPage {
        val params = linkedMapOf("page" to query.page.toString(), "limit" to query.limit.toString())
        listOf("search" to query.search, "site" to query.site, "collection" to query.collection, "category" to query.category)
            .forEach { (key, value) -> if (!value.isNullOrBlank()) params[key] = value }
        return decoder.page(request("/api/v1/live-products", params), query)
    }

    override suspend fun productSnapshot(after: Long, baseline: String?): ProviderSnapshot {
        require(after >= 0 && (baseline == null || baseline.length <= 512))
        val params = linkedMapOf("after" to after.toString(), "limit" to "100", "approved_only" to "1")
        if (baseline != null) params["baseline"] = baseline
        return decoder.snapshot(request("/api/v1/product-snapshot", params))
    }

    override suspend fun productChanges(cursor: String): ProviderChanges {
        require(cursor.length in 1..512)
        return decoder.changes(request("/api/v1/product-changes", linkedMapOf("cursor" to cursor, "limit" to "100", "approved_only" to "1")))
    }

    private suspend fun request(path: String, params: Map<String, String>): String =
        http.get(config.origin, path, params, mapOf("Authorization" to "Bearer ${config.apiKey}"))

    // Backend runs on JDK 25. Close at application stop, cancelling outstanding requests first.
    override fun close() = http.close()
}
