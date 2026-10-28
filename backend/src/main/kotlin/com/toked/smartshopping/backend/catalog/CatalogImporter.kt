package com.toked.smartshopping.backend.catalog

import com.toked.smartshopping.backend.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock

class CatalogImporter(private val db: Database, private val provider: CatalogProvider?, private val clock: Clock = Clock.systemUTC()) {
    private val normalizer = CatalogNormalizer(clock)
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    /** Explicit bounded operation; provider I/O finishes before a database transaction starts. */
    suspend fun sync(site: String? = null, maxPages: Int = 200): CatalogSyncResult {
        require(maxPages in 1..1000)
        val source = provider ?: throw ProviderFailure(ProviderFailureCode.UNCONFIGURED)
        val records = linkedMapOf<String, CatalogRecord>()
        val knownSites=linkedSetOf<String>()
        var received = 0; var rejected = 0; var partial = false; var done = false
        var firstTotal: Long? = null
        for (pageNumber in 1..maxPages) {
            val page = source.products(CatalogQuery(page = pageNumber, limit = 100, site = site))
            if (firstTotal == null) firstTotal = page.total
            if (firstTotal != page.total) partial = true
            partial = partial || page.rejectedItems > 0 || page.warnings.isNotEmpty()
            received += page.items.size + page.rejectedItems
            rejected += page.rejectedItems
            for (item in page.items) {
                if(site==null || item.website==site) knownSites+=item.website
                val record = if (site == null || item.website == site) normalizer.normalize(item) else null
                if (record == null) { rejected++; partial = true } else records[record.offer.ref.id] = record
            }
            if (received >= page.total || page.total == 0L) { done = true; break }
            if (page.items.isEmpty() && page.rejectedItems == 0) { partial = true; break }
        }
        if (!done) partial = true
        val now = clock.instant()
        val groups = records.values.groupBy { it.site }.toMutableMap()
        knownSites.forEach { if(it !in groups) groups[it]=emptyList() }
        if (site != null && site !in groups) groups[site] = emptyList()
        withContext(Dispatchers.IO) { db.transaction { c ->
            // Serialize cache writers only during commit, never while fetching a retailer.
            query(c, "SELECT version FROM catalog_cache_version WHERE id=1 FOR UPDATE").use { s -> s.executeQuery().close() }
            if (!partial) {
                if (site == null) execute(c, "UPDATE catalog_entries SET active=FALSE")
                else execute(c, "UPDATE catalog_entries SET active=FALSE WHERE retailer_id=?", CatalogNormalizer.retailerId(site))
            }
            for ((website, values) in groups) {
                val retailerId = CatalogNormalizer.retailerId(website)
                execute(c, "INSERT INTO shopping_retailers(id,name,status,code) VALUES (?,?,'ACTIVE',?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,status='ACTIVE'",
                    retailerId, CatalogNormalizer.retailerName(website), "CATALOGFLOW")
                execute(c, "INSERT INTO catalog_sources(retailer_id,site,status,fetched_at) VALUES (?,?,?,?) ON CONFLICT(retailer_id) DO UPDATE SET status=EXCLUDED.status,fetched_at=EXCLUDED.fetched_at,error_code=NULL,retry_until=NULL",
                    retailerId, website, if (partial) "PARTIAL" else if (values.isEmpty()) "EMPTY" else "READY", now)
            }
            for (record in records.values) {
                for (category in record.categories) execute(c, "INSERT INTO shopping_categories(id,name,status) VALUES (?,?,'ACTIVE') ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,status='ACTIVE'", category.id, category.name)
                record.brand?.let { execute(c, "INSERT INTO shopping_brands(id,name,status) VALUES (?,?,'ACTIVE') ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,status='ACTIVE'", it.id, it.name) }
                val product = record.product; val offer = record.offer
                execute(c, "INSERT INTO shopping_products(id,category_id,brand_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET category_id=EXCLUDED.category_id,brand_id=EXCLUDED.brand_id,payload=EXCLUDED.payload",
                    product.id, product.category_id, product.brand_id, json.encodeToString(product))
                execute(c, "INSERT INTO shopping_offers(kind,id,product_id,retailer_id,currency,payload) VALUES ('CATALOG',?,?,?,'VND',?::jsonb) ON CONFLICT(kind,id) DO UPDATE SET product_id=EXCLUDED.product_id,retailer_id=EXCLUDED.retailer_id,payload=EXCLUDED.payload",
                    offer.ref.id, product.id, offer.retailer_id, json.encodeToString(offer))
                execute(c, "INSERT INTO catalog_entries(offer_id,retailer_id,category_ids,active,fetched_at) VALUES (?,?,?,TRUE,?) ON CONFLICT(offer_id) DO UPDATE SET category_ids=EXCLUDED.category_ids,active=TRUE,fetched_at=EXCLUDED.fetched_at",
                    offer.ref.id, record.retailer.id, c.createArrayOf("text", record.categories.map { it.id }.toTypedArray()), now)
            }
            execute(c, "UPDATE catalog_cache_version SET version=version+1 WHERE id=1")
        } }
        return CatalogSyncResult(received, records.size, rejected, groups.size, partial)
    }
}
