package com.toked.smartshopping.backend.catalog

import com.toked.smartshopping.backend.*
import com.toked.smartshopping.backend.shopping.StoreSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode

class CatalogImporter(private val db: Database, private val provider: CatalogProvider?, private val clock: Clock = Clock.systemUTC()) {
    private val normalizer = CatalogNormalizer(clock)
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    /** Explicit bounded operation; provider I/O finishes before a database transaction starts. */
    suspend fun sync(site: String? = null, maxPages: Int = 200): CatalogSyncResult {
        require(maxPages in 1..1000)
        val source = provider ?: throw ProviderFailure(ProviderFailureCode.UNCONFIGURED)
        val progress = if (site == null && source is IncrementalCatalogProvider) withContext(Dispatchers.IO) {
            db.transaction { c -> query(c,"SELECT dataset,cursor FROM catalog_sync_state WHERE provider='catalogflow'").use { s ->
                s.executeQuery().use { r -> if (r.next()) CatalogProgress(r.getString(1),r.getString(2)) else null }
            } }
        } else null
        val delta = if (site == null && source is IncrementalCatalogProvider) CatalogDeltaSync.fetch(source,progress,maxPages) else null
        val sourceIdsById=delta?.products?.associate { it.id to it.sourceIds }.orEmpty()
        val recordsByUpstream=linkedMapOf<String,CatalogRecord>()
        val records = linkedMapOf<String, CatalogRecord>()
        val knownSites=linkedSetOf<String>()
        var received = 0; var consumed = 0L; var rejected = 0; var partial = false; var done = false
        var firstTotal: Long? = null
        if (delta != null) {
            received=delta.products.size+delta.deleted.size+delta.rejected;rejected=delta.rejected;done=true
            partial=delta.rejected>0
            for (item in delta.products) {
                knownSites+=item.website
                val record=normalizer.normalize(item)
                if (record==null) { rejected++;partial=true }
                else { records[record.offer.ref.id]=record;recordsByUpstream[item.id]=record }
            }
        } else for (pageNumber in 1..maxPages) {
            val page = source.products(CatalogQuery(page = pageNumber, limit = 100, site = site))
            if (firstTotal == null) firstTotal = page.total
            if (firstTotal != page.total) partial = true
            partial = partial || page.rejectedItems > 0 || page.warnings.isNotEmpty()
            received += page.items.size + page.rejectedItems
            consumed += page.upstreamCount
            rejected += page.rejectedItems
            for (item in page.items) {
                if(site==null || item.website==site) knownSites+=item.website
                val record = if (site == null || item.website == site) normalizer.normalize(item) else null
                if (record == null) { rejected++; partial = true } else records[record.offer.ref.id] = record
            }
            if (consumed >= page.total || page.total == 0L) { done = true; break }
            if (page.upstreamCount == 0) { partial = true; break }
        }
        if (!done) partial = true
        val branches = source.branches().filter { site == null || it.website == site }
        val now = clock.instant()
        val groups = records.values.groupBy { it.site }.toMutableMap()
        knownSites.forEach { if(it !in groups) groups[it]=emptyList() }
        if (site != null && site !in groups) groups[site] = emptyList()
        withContext(Dispatchers.IO) { db.transaction { c ->
            // Serialize cache writers only during commit, never while fetching a retailer.
            query(c, "SELECT version FROM catalog_cache_version WHERE id=1 FOR UPDATE").use { s -> s.executeQuery().close() }
            if (delta != null) {
                val actual=query(c,"SELECT dataset,cursor FROM catalog_sync_state WHERE provider='catalogflow'").use { s ->
                    s.executeQuery().use { r -> if (r.next()) CatalogProgress(r.getString(1),r.getString(2)) else null }
                }
                if (actual!=delta.previous) throw ProviderFailure(ProviderFailureCode.UNAVAILABLE,retryable=true)
                if (delta.reset) {
                    execute(c,"UPDATE catalog_entries SET active=FALSE WHERE retailer_id IN (SELECT id FROM shopping_retailers WHERE code='CATALOGFLOW')")
                    execute(c,"DELETE FROM catalog_provider_entries WHERE provider='catalogflow'")
                }
                for (id in delta.deleted) {
                    execute(c,"UPDATE catalog_entries SET active=FALSE WHERE offer_id IN (SELECT offer_id FROM catalog_provider_entries WHERE provider='catalogflow' AND upstream_id=?)",id)
                    execute(c,"DELETE FROM catalog_provider_entries WHERE provider='catalogflow' AND upstream_id=?",id)
                }
            }
            if (delta == null && !partial) {
                if (site == null) execute(c, "UPDATE catalog_entries SET active=FALSE WHERE retailer_id IN (SELECT id FROM shopping_retailers WHERE code='CATALOGFLOW')")
                else execute(c, "UPDATE catalog_entries SET active=FALSE WHERE retailer_id=?", CatalogNormalizer.retailerId(site))
            }
            for ((website, values) in groups) {
                val retailerId = CatalogNormalizer.retailerId(website)
                execute(c, "INSERT INTO shopping_retailers(id,name,status,code) VALUES (?,?,'ACTIVE',?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,status='ACTIVE'",
                    retailerId, CatalogNormalizer.retailerName(website), "CATALOGFLOW")
                execute(c, "INSERT INTO catalog_sources(retailer_id,site,status,fetched_at) VALUES (?,?,?,?) ON CONFLICT(retailer_id) DO UPDATE SET status=EXCLUDED.status,fetched_at=EXCLUDED.fetched_at,error_code=NULL,retry_until=NULL",
                    retailerId, website, if (partial) "PARTIAL" else if (values.isEmpty()) "EMPTY" else "READY", now)
            }
            // shortcut: branches removed upstream stay listed (stores have no status column); add one if CrawlData starts retiring branches.
            for (branch in branches.filter { it.website in groups }) {
                val retailerId = CatalogNormalizer.retailerId(branch.website)
                val id = CatalogNormalizer.id("store", branch.website, branch.externalId)
                // CrawlData publishes no address or coordinates; location stays null rather than guessed.
                val payload = json.encodeToString(StoreSnapshot(id, 1, retailerId, branch.name, null, null, "CRAWLDATA", "PUBLIC"))
                execute(c, """INSERT INTO shopping_stores(id,retailer_id,visibility,payload) VALUES (?,?,'PUBLIC',?::jsonb)
                    ON CONFLICT(id) DO UPDATE SET payload=jsonb_set(EXCLUDED.payload,'{version}',to_jsonb((shopping_stores.payload->>'version')::int+1))
                    WHERE shopping_stores.payload->>'name' IS DISTINCT FROM EXCLUDED.payload->>'name'""", id, retailerId, payload)
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
            if (delta != null) {
                for ((id,record) in recordsByUpstream) execute(c,
                    "INSERT INTO catalog_provider_entries(provider,upstream_id,offer_id,source_ids) VALUES ('catalogflow',?,?,?) ON CONFLICT(provider,upstream_id) DO UPDATE SET offer_id=EXCLUDED.offer_id,source_ids=EXCLUDED.source_ids",id,record.offer.ref.id,c.createArrayOf("text",sourceIdsById[id].orEmpty().toTypedArray()))
                for (observation in delta.observations) {
                    val seconds=try { observation.observedAt.setScale(0,RoundingMode.DOWN).longValueExact() }
                        catch (_: Exception) { throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE) }
                    val instant=try { Instant.ofEpochSecond(seconds) } catch (_: Exception) { throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE) }
                    if (instant<Instant.parse("2000-01-01T00:00:00Z") || instant>now.plusSeconds(300)) throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE)
                    val freshness=if (instant<now.minusSeconds(86400)) "STALE" else "FRESH"
                    execute(c,"""UPDATE shopping_offers SET payload=jsonb_set(jsonb_set(jsonb_set(payload,
                        '{observed_at}',to_jsonb(?::text)),'{freshness}',to_jsonb(?::text)),
                        '{version}',to_jsonb(('cf_observation_'||md5(id||?))::text))
                        WHERE kind='CATALOG' AND id IN (SELECT offer_id FROM catalog_provider_entries
                        WHERE provider='catalogflow' AND ?=ANY(source_ids))
                        AND coalesce((payload->>'observed_at')::timestamptz,'epoch'::timestamptz)<=?::timestamptz""",
                        instant.toString(),freshness,instant.toString(),observation.sourceId,instant.toString())
                    execute(c,"UPDATE catalog_entries SET fetched_at=? WHERE offer_id IN (SELECT offer_id FROM catalog_provider_entries WHERE provider='catalogflow' AND ?=ANY(source_ids))",now,observation.sourceId)
                }
                execute(c,"INSERT INTO catalog_sync_state(provider,dataset,cursor) VALUES ('catalogflow',?,?) ON CONFLICT(provider) DO UPDATE SET dataset=EXCLUDED.dataset,cursor=EXCLUDED.cursor",delta.dataset,delta.cursor)
            }
            execute(c, "UPDATE catalog_cache_version SET version=version+1 WHERE id=1")
        } }
        return CatalogSyncResult(received, records.size, rejected, groups.size, partial)
    }
}
