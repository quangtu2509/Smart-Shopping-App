package com.toked.smartshopping.backend.shopping

import com.toked.smartshopping.backend.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class MutationRequest(val method: String, val path: String, val key: String, val body: JsonElement, val query: JsonObject = JsonObject(emptyMap()))
data class MutationResult(val status: Int, val data: JsonElement = JsonNull, val location: String? = null)

internal val shoppingJson = Json { encodeDefaults = true; explicitNulls = true }

/** Server-side boundary. Blocks are trusted services; it deliberately registers no HTTP routes. */
class ShoppingData(private val db: Database, private val auth: AuthService, private val clock: Clock = Clock.systemUTC(),
    private val customQuantitySteps: Map<String, String> = emptyMap()) {
    fun <T> read(access: String, action: ShoppingRead.() -> T): T = db.transaction { c ->
        ShoppingRead(c, auth.authenticatedOwner(c, access)).action()
    }

    fun mutate(access: String, request: MutationRequest, action: ShoppingWrite.() -> MutationResult): MutationResult = db.transaction { c ->
        // Auth locks the account before its session. Reuse that ordering for all shopping mutations.
        val owner = auth.authenticatedOwner(c, access)
        val read = ShoppingRead(c, owner)
        if (request.method !in setOf("POST", "PATCH", "PUT", "DELETE") || !request.path.startsWith("/") || request.path.startsWith("/auth/"))
            throw ApiFailure(400, "MALFORMED_REQUEST")
        // A replay still has to pass current resource ownership, before looking at its cached response.
        if (request.path.startsWith("/plans/")) read.plan(parseId(request.path.split('/')[2]))
        if (request.key.isBlank()) throw ApiFailure(400, "IDEMPOTENCY_KEY_REQUIRED")
        if (request.key.length > 200) invalid("Idempotency-Key", "TOO_LONG")
        val hash = Security().digest(canonical(buildJsonObject { put("body", request.body); put("query", request.query) }).toString())
        val now = clock.instant()
        execute(c, "DELETE FROM shopping_mutations WHERE owner_id=? AND created_at < ?", owner, now.minusSeconds(86400))
        query(c, "SELECT * FROM shopping_mutations WHERE owner_id=? AND method=? AND path=? AND request_key=?",
            owner, request.method, request.path, request.key).use { s -> s.executeQuery().use { r ->
            if (r.next()) {
                if (r.getString("request_hash") != hash) throw ApiFailure(409, "IDEMPOTENCY_KEY_REUSED")
                return@transaction MutationResult(r.getInt("status"), shoppingJson.parseToJsonElement(r.getString("data")), r.getString("location"))
            }
        } }
        val result = ShoppingWrite(c, owner, clock, customQuantitySteps).action()
        require(result.status in 200..299)
        businessResult(result.data)
        execute(c, "INSERT INTO shopping_mutations(owner_id,method,path,request_key,request_hash,status,data,location,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
            owner, request.method, request.path, request.key, hash, result.status, result.data.toString(), result.location, now)
        result
    }
    private fun businessResult(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                require(key.lowercase(java.util.Locale.ROOT) !in setOf("password", "password_hash", "access_token", "refresh_token", "id_token", "reset_token", "authorization")) {
                    "Credentials cannot enter a shopping replay record"
                }
                businessResult(item)
            }
            is JsonArray -> value.forEach(::businessResult)
            is JsonPrimitive -> Unit
        }
    }
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        is JsonPrimitive -> value
    }
}

internal fun parseId(value: String): UUID = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw ApiFailure(404, "RESOURCE_NOT_FOUND") }
internal data class PlanRow(val id: UUID, val version: Int, val revision: Int, val current: Int?, val currency: String, val budget: String,
    val maxStores: Int, val status: PlanStatus, val catalog: CatalogStatus, val created: String, val updated: String)
internal data class ItemRecord(val item: ShoppingItem, val product: Product?, val store: StoreSnapshot?, val step: String)
internal data class SelectedOffer(val offer: Offer, val product: Product?, val store: StoreSnapshot?, val customName: String?, val unit: String, val step: String, val category: String)

open class ShoppingRead internal constructor(protected val c: Connection, protected val owner: UUID) {
    protected val money: MoneyRules by lazy {
        MoneyRules(query(c, "SELECT code,minor_units FROM shopping_currencies").use { s -> s.executeQuery().use { r -> buildMap {
            while (r.next()) put(r.getString(1), r.getInt(2))
        } } })
    }
    internal fun row(id: UUID): PlanRow = query(c, "SELECT * FROM shopping_plans WHERE id=? AND owner_id=? FOR UPDATE", id, owner).use { s ->
        s.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
            PlanRow(id, r.getInt("version"), r.getInt("revision"), r.getObject("current_finalized_revision") as Int?, r.getString("currency"),
                r.getBigDecimal("budget").toPlainString(), r.getInt("max_stores"), PlanStatus.valueOf(r.getString("status")), CatalogStatus.valueOf(r.getString("catalog_status")),
                r.instant("created_at").toString(), r.instant("updated_at").toString())
        }
    }
    internal fun selections(id: UUID): List<Selection> = query(c, "SELECT retailer_id,store_id FROM shopping_plan_selections WHERE plan_id=? ORDER BY position", id).use { s ->
        s.executeQuery().use { r -> buildList { while (r.next()) add(Selection(r.getString(1), r.getString(2))) } }
    }
    internal fun categories(id: UUID): List<String> = query(c, "SELECT category_id FROM shopping_plan_categories WHERE plan_id=? ORDER BY category_id", id).use { s ->
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
    internal fun records(id: UUID, currency: String): List<ItemRecord> = query(c, "SELECT * FROM shopping_items WHERE plan_id=? ORDER BY id", id).use { s -> s.executeQuery().use { r -> buildList {
        while (r.next()) {
            val offer = r.getString("selected_price_snapshot")?.let { shoppingJson.decodeFromString<Offer>(it) }
            if (offer != null && offer.currency != currency) invalid("currency", "CURRENCY_MISMATCH")
            val quantity = r.getBigDecimal("quantity").stripTrailingZeros().toPlainString()
            val item = ShoppingItem(r.uuid("id").toString(), r.getString("product_id"), r.getString("custom_name"), offer?.ref, quantity,
                r.getString("unit"), Priority.valueOf(r.getString("priority")), offer, money.subtotal(offer?.price, quantity, currency))
            add(ItemRecord(item, r.getString("product_snapshot")?.let { shoppingJson.decodeFromString<Product>(it) },
                r.getString("store_snapshot")?.let { shoppingJson.decodeFromString<StoreSnapshot>(it) }, r.getBigDecimal("quantity_step").toPlainString()))
        }
    } } }
    fun plan(id: UUID): PlanView {
        val p = row(id)
        val items = records(id, p.currency).map { it.item }
        val summary = money.summary(p.budget, p.currency, items.map { it.id to it.subtotal })
        return PlanView(Plan(id.toString(), p.version, p.revision, p.status, p.currency, summary.budget, selections(id), categories(id),
            p.maxStores, summary, p.catalog, p.created, p.updated), items, p.current, warnings = summary.warnings)
    }
    internal fun plans(status: String?, limit: Int, cursor: String?): Page<Plan> {
        pageLimit(limit)
        if (status != null && PlanStatus.entries.none { it.name == status }) invalid("status", "INVALID_ENUM")
        val token = ShoppingCursor(listOf(owner.toString(), "plans", status), cursor)
        val after = token.after?.let {
            val pieces = it.split('|')
            if (pieces.size != 2) throw ApiFailure(400, "INVALID_CURSOR")
            try { Instant.parse(pieces[0]) to UUID.fromString(pieces[1]) }
            catch (_: java.time.format.DateTimeParseException) { throw ApiFailure(400, "INVALID_CURSOR") }
            catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
        }
        val ids = query(c, "SELECT id FROM shopping_plans WHERE owner_id=? AND (?::text IS NULL OR status=?) AND (?::timestamptz IS NULL OR (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT ?",
            owner, status, status, after?.first, after?.first, after?.second, limit + 1).use { s -> s.executeQuery().use { r -> buildList { while (r.next()) add(r.uuid("id")) } } }
        val items = ids.take(limit).map { plan(it).plan }
        return Page(items, if (ids.size > limit) token.next("${items.last().created_at}|${items.last().id}") else null)
    }
    internal fun storedStores(bbox: String?, q: String?, retailers: String?, limit: Int, cursor: String?): StoredMapPage {
        pageLimit(limit); searchQuery(q)
        val coordinates = bbox?.split(',')?.map { it.toDoubleOrNull() ?: invalid("bbox", "INVALID_BBOX") }
            ?: invalid("bbox", "REQUIRED")
        if (coordinates.size != 4 || coordinates.any { !it.isFinite() }) invalid("bbox", "INVALID_BBOX")
        val (west, south, east, north) = coordinates
        if (west !in -180.0..180.0 || east !in -180.0..180.0 || south !in -90.0..90.0 || north !in -90.0..90.0 || west >= east || south >= north)
            invalid("bbox", "INVALID_BBOX")
        val retailerIds = retailers?.split(',') ?: emptyList()
        if (retailerIds.size > 3 || retailerIds.any { it.isBlank() } || retailerIds.distinct().size != retailerIds.size) invalid("retailer_ids", "INVALID_SELECTION")
        val token = ShoppingCursor(listOf(owner.toString(), "stores", bbox, q, retailers), cursor)
        val rows = query(c, """SELECT payload,owner_id FROM shopping_stores WHERE (visibility='PUBLIC' OR owner_id=?)
            AND (payload->'location'->>'longitude')::numeric BETWEEN ? AND ?
            AND (payload->'location'->>'latitude')::numeric BETWEEN ? AND ?
            AND (?::text IS NULL OR position(lower(?) in lower(coalesce(payload->>'name','')||' '||coalesce(payload->>'address','')))>0)
            AND (cardinality(?::text[])=0 OR retailer_id=ANY(?::text[])) AND (?::text IS NULL OR id>?) ORDER BY id LIMIT ?""",
            owner, west, east, south, north, q, q, c.createArrayOf("text", retailerIds.toTypedArray()),
            c.createArrayOf("text", retailerIds.toTypedArray()), token.after, token.after, limit + 1).use { s -> s.executeQuery().use { r -> buildList {
                while (r.next()) add(shoppingJson.decodeFromString<StoreSnapshot>(r.getString(1)).copy(can_edit = r.getObject("owner_id") == owner))
            } } }
        val items = rows.take(limit)
        val attribution = if (items.any { it.source in setOf("OSM", "PROVIDER") })
            listOf(StoreAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")) else emptyList()
        val result = StoredMapResult(items, if (items.isEmpty()) "EMPTY" else "PARTIAL", attribution,
            listOf(ShoppingWarning("PLACES_NOT_CONNECTED", "Chỉ hiển thị cửa hàng đã có dữ liệu. Tìm địa điểm và cửa hàng gần nhất chưa được kết nối.")))
        return StoredMapPage(result, if (rows.size > limit) token.next(items.last().id) else null)
    }
    fun revision(id: UUID, revision: Int): RevisionSnapshot {
        row(id)
        return query(c, "SELECT payload FROM shopping_plan_revisions WHERE plan_id=? AND revision=?", id, revision).use { s -> s.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
            shoppingJson.decodeFromString(r.getString(1))
        } }
    }
    internal fun store(id: String): StoreSnapshot = query(c, "SELECT payload,owner_id FROM shopping_stores WHERE id=? AND (visibility='PUBLIC' OR owner_id=?)", id, owner).use { s ->
        s.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
            shoppingJson.decodeFromString<StoreSnapshot>(r.getString(1)).copy(can_edit = r.getObject("owner_id") == owner)
        }
    }
    internal fun selected(ref: OfferRef): SelectedOffer {
        return query(c, """SELECT o.*,p.payload AS product_payload,sp.custom_name AS private_name,sp.unit AS private_unit,
            sp.quantity_step AS private_step,sp.category_id AS private_category,sp.product_id AS private_product
            FROM shopping_offers o LEFT JOIN shopping_products p ON p.id=o.product_id
            LEFT JOIN shopping_store_products sp ON sp.id=o.store_product_id AND sp.store_id=o.store_id
            WHERE o.kind=? AND o.id=? FOR SHARE OF o""", ref.kind.name, ref.id).use { s -> s.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
            val product = r.getString("product_payload")?.let { shoppingJson.decodeFromString<Product>(it) }
            val offer = shoppingJson.decodeFromString<Offer>(r.getString("payload")).copy(product = product)
            val store = offer.store_id?.let(::store)
            when (ref.kind) {
                OfferKind.CATALOG -> {
                    if (store != null && (store.visibility != "PUBLIC" || store.retailer_id != offer.retailer_id)) invalid("offer_ref", "INVALID_STORE_RELATION")
                    if (product == null) invalid("offer_ref", "PRODUCT_REQUIRED")
                }
                OfferKind.PRIVATE -> {
                    if (store?.visibility != "PRIVATE" || !store.can_edit) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    if (r.getString("private_product") != product?.id) invalid("offer_ref", "INVALID_STORE_PRODUCT")
                }
            }
            SelectedOffer(offer, product, store, r.getString("private_name"), product?.unit ?: r.getString("private_unit"),
                product?.quantity_step ?: r.getBigDecimal("private_step").toPlainString(), product?.category_id ?: r.getString("private_category"))
        } }
    }
}
