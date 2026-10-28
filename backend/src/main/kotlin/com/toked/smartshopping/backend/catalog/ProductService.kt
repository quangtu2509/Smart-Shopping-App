package com.toked.smartshopping.backend.catalog

import com.toked.smartshopping.backend.*
import com.toked.smartshopping.backend.shopping.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Catalog reads are from PostgreSQL snapshots; network work belongs to accepted refresh jobs. */
class ProductService(private val db: Database, private val auth: AuthService, provider: CatalogProvider?, private val clock: Clock = Clock.systemUTC()) {
    private val importer = CatalogImporter(db, provider, clock)
    private val data = ShoppingData(db, auth, clock)

    suspend fun catalog(access: String, planId: String, filter: CatalogFilter): CatalogPage = withContext(Dispatchers.IO) {
        db.transaction { c ->
            val owner = auth.authenticatedOwner(c, access)
            val view = ShoppingRead(c, owner).plan(parseId(planId))
            page(c, owner, view.plan, filter)
        }
    }
    suspend fun detail(access: String, planId: String, productId: String): ProductDetail = withContext(Dispatchers.IO) {
        db.transaction { c ->
            val owner = auth.authenticatedOwner(c, access)
            val view = ShoppingRead(c, owner).plan(parseId(planId))
            val result = page(c, owner, view.plan, CatalogFilter(limit = 100), productId = productId)
            val product = result.items.firstOrNull()?.product ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
            ProductDetail(product, result.items, result.warnings)
        }
    }

    suspend fun requestRefresh(access: String, planId: String, key: String?, body: JsonObject): MutationResult = withContext(Dispatchers.IO) {
        if (body.keys != setOf("expected_version")) throw ApiFailure(400, "MALFORMED_REQUEST")
        val expected = (body["expected_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (expected == null || expected <= 0) invalid("expected_version", "INVALID_INTEGER")
        data.mutate(access, MutationRequest("POST", "/plans/$planId/catalog-refresh", key.orEmpty(), body)) {
            val view = queueCatalogRefresh(parseId(planId), expected)
            MutationResult(202, shoppingJson.encodeToJsonElement(CatalogRefresh(view.plan)))
        }
    }
    fun ticket(result: MutationResult): CatalogTicket {
        val plan = result.data.jsonObject.getValue("plan").jsonObject
        return CatalogTicket(plan.getValue("id").jsonPrimitive.content, plan.getValue("version").jsonPrimitive.int)
    }
    suspend fun pending(access: String, planId: String): CatalogTicket? = withContext(Dispatchers.IO) {
        db.transaction { c ->
            val owner=auth.authenticatedOwner(c,access)
            val plan=ShoppingRead(c,owner).plan(parseId(planId)).plan
            if(plan.catalog_status!=CatalogStatus.LOADING) null else
                query(c,"SELECT version FROM catalog_refresh_jobs WHERE plan_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING') ORDER BY version DESC LIMIT 1",parseId(plan.id),owner).use { s ->
                    s.executeQuery().use { r -> if(r.next()) CatalogTicket(plan.id,r.getInt(1)) else null }
                }
        }
    }

    /** Caller (Application scope) owns cancellation. A DB lease prevents duplicate work across polling/processes. */
    suspend fun refresh(ticket: CatalogTicket) {
        val id = parseId(ticket.planId)
        val owner = withContext(Dispatchers.IO) { db.transaction { c ->
            query(c, "UPDATE catalog_refresh_jobs SET status='RUNNING',lease_until=? WHERE plan_id=? AND version=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?)) RETURNING owner_id",
                clock.instant().plusSeconds(300), id, ticket.version, clock.instant()).use { s -> s.executeQuery().use { r -> if (r.next()) r.uuid("owner_id") else null } }
        } } ?: return
        try {
            val sites = withContext(Dispatchers.IO) { db.transaction { c ->
                lockOwner(c,owner)
                val plan = ShoppingRead(c, owner).plan(id).plan
                if (plan.version != ticket.version || plan.catalog_status != CatalogStatus.LOADING) emptyList() else
                    plan.selections.mapNotNull { it.retailer_id }.distinct().map { retailer ->
                        retailer to query(c, "SELECT site,retry_until,error_code,status,fetched_at FROM catalog_sources WHERE retailer_id=?", retailer).use { s -> s.executeQuery().use { r ->
                            if (r.next()) RefreshSource(r.getString("site"),r.getTimestamp("retry_until")?.toInstant(),r.getTimestamp("fetched_at")?.toInstant(),r.getString("status")) else null
                        } }
                    }
            } }
            for ((retailer, source) in sites) {
                if (source == null) { sourceFailure(retailer, ProviderFailure(ProviderFailureCode.UNCONFIGURED)); continue }
                if (source.retry?.isAfter(clock.instant()) == true) continue
                // Reuse a recently verified cache when entering a Plan; explicit retries still honor upstream cooldown.
                val legacy=withContext(Dispatchers.IO){db.transaction {c->query(c,"SELECT 1 FROM catalog_entries e JOIN shopping_offers o ON o.kind=e.kind AND o.id=e.offer_id WHERE e.active AND e.retailer_id=? AND left(o.payload->>'version',10)<>'cf_price2_' LIMIT 1",retailer).use {s->s.executeQuery().use {it.next()}}}}
                if(!legacy && source.status in setOf("READY","EMPTY") && source.fetched?.isAfter(clock.instant().minusSeconds(900))==true) continue
                try { withTimeout(60000) { importer.sync(source.site) } }
                catch (_: TimeoutCancellationException) { sourceFailure(retailer, ProviderFailure(ProviderFailureCode.TIMEOUT, retryable = true)) }
                catch (failure: ProviderFailure) { sourceFailure(retailer, failure) }
            }
            withContext(Dispatchers.IO) { db.transaction { c ->
                lockOwner(c,owner)
                val read = ShoppingRead(c, owner)
                val current = read.plan(id).plan
                if (current.version == ticket.version && current.catalog_status == CatalogStatus.LOADING) {
                    val status = page(c, owner, current, CatalogFilter(), ignoreLoading = true).status
                    ShoppingWrite(c, owner, clock, emptyMap()).catalogState(id, ticket.version, status)
                } else failObsoleteLoading(c,owner,current,ticket)
                execute(c, "UPDATE catalog_refresh_jobs SET status='DONE',lease_until=NULL WHERE plan_id=? AND version=?", id, ticket.version)
            } }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { db.transaction { c ->
                execute(c, "UPDATE catalog_refresh_jobs SET status='QUEUED',lease_until=NULL WHERE plan_id=? AND version=? AND status='RUNNING'", id, ticket.version)
            } }
            throw cancelled
        } catch (_: Exception) {
            // Unexpected internal failures are safe and recoverable, and must not leave a Plan stuck loading.
            withContext(Dispatchers.IO) { db.transaction { c ->
                lockOwner(c,owner)
                val current = ShoppingRead(c, owner).plan(id).plan
                if (current.version == ticket.version && current.catalog_status == CatalogStatus.LOADING)
                    ShoppingWrite(c, owner, clock, emptyMap()).catalogState(id, ticket.version, CatalogStatus.FAILED)
                else failObsoleteLoading(c,owner,current,ticket)
                execute(c, "UPDATE catalog_refresh_jobs SET status='DONE',lease_until=NULL WHERE plan_id=? AND version=?", id, ticket.version)
            } }
        }
    }

    private suspend fun sourceFailure(retailer: String, failure: ProviderFailure) = withContext(Dispatchers.IO) { db.transaction { c ->
        // All cache writers take generation first, matching CatalogImporter and avoiding lock-order inversion.
        query(c,"SELECT version FROM catalog_cache_version WHERE id=1 FOR UPDATE").use {s->s.executeQuery().close()}
        execute(c, "UPDATE catalog_sources SET status='FAILED',error_code=?,retry_until=? WHERE retailer_id=?",
            "PROVIDER_${failure.code}", failure.retryAfterSeconds?.let { clock.instant().plusSeconds(it.toLong()) }, retailer)
        execute(c, "UPDATE catalog_cache_version SET version=version+1 WHERE id=1")
    } }

    private fun page(c: Connection, owner: UUID, plan: Plan, filter: CatalogFilter, productId: String? = null, ignoreLoading: Boolean = false): CatalogPage {
        pageLimit(filter.limit); searchQuery(filter.query)
        if (filter.sort !in setOf("RELEVANCE", "PRICE_ASC", "PRICE_DESC")) invalid("sort", "INVALID_ENUM")
        if (listOf(filter.retailer, filter.store, filter.category, filter.brand).any { it != null && it.length > 200 }) invalid("filter", "TOO_LONG")
        var retailers = plan.selections.mapNotNull { it.retailer_id }.distinct()
        filter.retailer?.let { if (it !in retailers) invalid("retailer_id", "OUTSIDE_PLAN"); retailers = listOf(it) }
        filter.category?.let { if (it !in plan.category_ids) invalid("category_id", "OUTSIDE_PLAN") }
        if (filter.store != null) {
            val store = ShoppingRead(c, owner).store(filter.store)
            if (plan.selections.none { it.store_id == store.id || (it.store_id == null && it.retailer_id == store.retailer_id) }) invalid("store_id", "OUTSIDE_PLAN")
            if (filter.retailer != null && filter.retailer != store.retailer_id) invalid("store_id", "OUTSIDE_PLAN")
            retailers = retailers.filter { it == store.retailer_id }
        }
        val categories = filter.category?.let(::listOf) ?: plan.category_ids
        // Take a shared lock so a cache generation and its rows stay stable for this page.
        val generation = query(c, "SELECT version FROM catalog_cache_version WHERE id=1 FOR SHARE").use { s -> s.executeQuery().use { it.next(); it.getLong(1) } }
        val scope = listOf(owner.toString(), plan.id, plan.version.toString(), generation.toString(), filter.retailer, filter.store,
            filter.category, filter.query, filter.brand, filter.sort, filter.limit.toString(), productId)
        val cursor = ShoppingCursor(scope, filter.cursor)
        val offset = cursor.after?.toIntOrNull() ?: if (cursor.after == null) 0 else throw ApiFailure(400, "INVALID_CURSOR")
        if (offset !in 0..1_000_000) throw ApiFailure(400, "INVALID_CURSOR")
        val args = arrayOf(c.createArrayOf("text", retailers.toTypedArray()), c.createArrayOf("text", categories.toTypedArray()),
            plan.currency, filter.query, filter.query, filter.brand, filter.brand, productId, productId)
        val from = """FROM catalog_entries e JOIN shopping_offers o ON o.kind='CATALOG' AND o.id=e.offer_id
            JOIN shopping_products p ON p.id=o.product_id LEFT JOIN shopping_brands b ON b.id=p.brand_id
            JOIN catalog_sources s ON s.retailer_id=e.retailer_id
            WHERE e.active AND e.retailer_id=ANY(?::text[]) AND e.category_ids && ?::text[] AND o.currency=?
            AND (?::text IS NULL OR position(lower(?) in lower(concat(p.payload->>'name',' ',b.name)))>0)
            AND (?::text IS NULL OR p.brand_id=?) AND (?::text IS NULL OR p.id=?)"""
        val results = mutableListOf<RetailerCatalogResult>()
        val warnings = mutableListOf<ShoppingWarning>()
        for (retailer in retailers) {
            val source = query(c, "SELECT status,error_code,retry_until,fetched_at FROM catalog_sources WHERE retailer_id=?", retailer).use { s -> s.executeQuery().use { r ->
                if (!r.next()) null else SourceStatus(r.getString("status"), r.getString("error_code"), r.getTimestamp("fetched_at")?.toInstant(),
                    r.getTimestamp("retry_until")?.toInstant()?.let { Duration.between(clock.instant(), it).seconds.coerceAtLeast(0).toInt() })
            } }
            val countArgs = args.copyOf(); countArgs[0] = c.createArrayOf("text", arrayOf(retailer))
            val count = query(c, "SELECT count(*) $from", *countArgs).use { s -> s.executeQuery().use { it.next(); it.getInt(1) } }
            val old = source?.fetched?.isBefore(clock.instant().minusSeconds(900)) == true
            val error = source?.error ?: if (source == null) "CATALOG_NOT_SYNCED" else null
            val status = when {
                error != null -> if (count > 0) CatalogStatus.PARTIAL else CatalogStatus.FAILED
                source?.status == "PARTIAL" || old -> CatalogStatus.PARTIAL
                count == 0 -> CatalogStatus.EMPTY
                else -> CatalogStatus.READY
            }
            results += RetailerCatalogResult(retailer, status, error, source?.retry?.takeIf { it > 0 })
            if (error != null) warnings += ShoppingWarning(error, "Chưa cập nhật được dữ liệu của một nơi mua. Dữ liệu đã lưu được giữ lại.", listOf(retailer))
            if (old) warnings += ShoppingWarning("CACHE_STALE", "Đang dùng dữ liệu đã lưu; hãy kiểm tra độ mới của giá.", listOf(retailer))
        }
        val priceOrder="CASE WHEN left(o.payload->>'version',10)='cf_price2_' THEN (o.payload->>'price')::numeric ELSE NULL END"
        val order = when (filter.sort) { "PRICE_ASC" -> "$priceOrder ASC NULLS LAST,"; "PRICE_DESC" -> "$priceOrder DESC NULLS LAST,"; else -> "" }
        val offers = query(c, "SELECT o.payload,e.fetched_at,s.error_code,b.name AS brand_name $from ORDER BY ${order}o.id LIMIT ? OFFSET ?", *args, filter.limit + 1, offset).use { s -> s.executeQuery().use { r -> buildList {
            while (r.next()) {
                var offer = shoppingJson.decodeFromString<Offer>(r.getString("payload"))
                if(!offer.version.startsWith("cf_price2_")) offer=offer.copy(price=null,warnings=offer.warnings+ShoppingWarning("STORE_PRICE_SCOPE_UNVERIFIED","Cache cũ chưa được xác minh phạm vi giá; hãy cập nhật sản phẩm."))
                offer=offer.copy(product=offer.product?.let {it.copy(brand_name=it.brand_name ?: r.getString("brand_name"))})
                if (r.instant("fetched_at").isBefore(clock.instant().minusSeconds(900)) || r.getString("error_code") != null)
                    offer = offer.copy(freshness = if (offer.freshness == Freshness.UNKNOWN) Freshness.UNKNOWN else Freshness.STALE)
                if (filter.store != null || plan.selections.any { it.store_id != null && it.retailer_id == offer.retailer_id })
                    offer = offer.copy(warnings = offer.warnings + ShoppingWarning("RETAILER_PRICE_NOT_STORE_CONFIRMED", "Giá theo chuỗi, chưa xác nhận riêng cho chi nhánh."))
                add(offer)
            }
        } } }
        if (plan.selections.any { it.retailer_id == null }) warnings += ShoppingWarning("PRIVATE_CATALOG_UNAVAILABLE", "Chưa có dữ liệu catalog cho cửa hàng riêng trong kế hoạch.")
        val status = when {
            !ignoreLoading && plan.catalog_status == CatalogStatus.LOADING -> CatalogStatus.LOADING
            results.any { it.status == CatalogStatus.FAILED } && offers.isEmpty() && results.none { it.status in setOf(CatalogStatus.READY, CatalogStatus.PARTIAL) } -> CatalogStatus.FAILED
            results.any { it.status in setOf(CatalogStatus.FAILED, CatalogStatus.PARTIAL) } || warnings.isNotEmpty() -> CatalogStatus.PARTIAL
            offers.isEmpty() -> CatalogStatus.EMPTY
            offers.any { it.price == null } -> CatalogStatus.PARTIAL
            else -> CatalogStatus.READY
        }
        return CatalogPage(offers.take(filter.limit), if (offers.size > filter.limit) cursor.next((offset + filter.limit).toString()) else null, status, results, warnings)
    }
    private data class SourceStatus(val status: String, val error: String?, val fetched: java.time.Instant?, val retry: Int?)
    private fun lockOwner(c: Connection,owner: UUID) {
        query(c,"SELECT id FROM app_users WHERE id=? FOR UPDATE",owner).use { s -> s.executeQuery().use { if(!it.next()) throw ApiFailure(404,"RESOURCE_NOT_FOUND") } }
    }
    private fun failObsoleteLoading(c:Connection,owner:UUID,current:Plan,ticket:CatalogTicket) {
        if(current.catalog_status!=CatalogStatus.LOADING || current.version==ticket.version) return
        val newer=query(c,"SELECT 1 FROM catalog_refresh_jobs WHERE plan_id=? AND version=? AND status IN ('QUEUED','RUNNING')",parseId(current.id),current.version).use {s->s.executeQuery().use {it.next()}}
        if(!newer) ShoppingWrite(c,owner,clock,emptyMap()).catalogState(parseId(current.id),current.version,CatalogStatus.FAILED)
    }
    private data class RefreshSource(val site: String,val retry: java.time.Instant?,val fetched: java.time.Instant?,val status: String)
}
