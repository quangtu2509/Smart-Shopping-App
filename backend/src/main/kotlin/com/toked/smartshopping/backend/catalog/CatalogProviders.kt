package com.toked.smartshopping.backend.catalog

/** `CATALOG_PROVIDER` selects the catalog source. Unset keeps CatalogFlow, the pre-existing behavior. */
enum class CatalogSource(val label: String, val maxSyncPages: Int) {
    // 100 records per page, bounded at 100k. The CatalogFlow snapshot (crawldata-smoky, ~67k items) needs ~671 pages;
    // 200 stopped every bootstrap with UNAVAILABLE because the cursor is saved only after a complete snapshot.
    CATALOGFLOW("CatalogFlow", 1000),
    CRAWLDATA("CrawlData", 1000),
}

class ConfiguredCatalog internal constructor(val source: CatalogSource, val provider: CatalogProvider, private val closeable: AutoCloseable) : AutoCloseable {
    override fun close() = closeable.close()
}

object CatalogProviders {
    fun source(environment: Map<String, String> = System.getenv()): CatalogSource =
        when (environment["CATALOG_PROVIDER"]?.trim()?.lowercase()) {
            null, "", "catalogflow" -> CatalogSource.CATALOGFLOW
            "crawldata" -> CatalogSource.CRAWLDATA
            else -> throw IllegalArgumentException("CATALOG_PROVIDER must be catalogflow or crawldata")
        }

    /** Null only when CatalogFlow is selected without credentials (catalog optional, as before). */
    fun fromEnvironment(environment: Map<String, String> = System.getenv()): ConfiguredCatalog? = when (source(environment)) {
        CatalogSource.CATALOGFLOW -> CatalogFlowConfig.fromEnvironment(environment)?.let { config ->
            CatalogFlowClient(config).let { ConfiguredCatalog(CatalogSource.CATALOGFLOW, it, it) }
        }
        CatalogSource.CRAWLDATA -> CrawlDataClient(CrawlDataConfig.fromEnvironment(environment)).let {
            ConfiguredCatalog(CatalogSource.CRAWLDATA, it, it)
        }
    }
}
