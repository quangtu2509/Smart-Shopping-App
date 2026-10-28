package com.toked.smartshopping.backend.shopping

import com.toked.smartshopping.backend.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Only available inside ShoppingData.mutate; callers must not retain this transaction-scoped object. */
class ShoppingWrite internal constructor(c: Connection, owner: UUID, private val clock: Clock,
    private val customQuantitySteps: Map<String, String>) : ShoppingRead(c, owner) {
    private fun expect(p: PlanRow, expected: Int) {
        if (expected != p.version) throw ApiFailure(409, "VERSION_CONFLICT", details = buildJsonObject { put("current_version", p.version) })
    }
    private fun editable(p: PlanRow) {
        when (p.status) {
            PlanStatus.DRAFT, PlanStatus.BROWSING_PRODUCTS, PlanStatus.BUILDING_LIST, PlanStatus.COMPARING, PlanStatus.OPTIMIZING -> Unit
            PlanStatus.LOADING_PRODUCTS, PlanStatus.FINALIZED, PlanStatus.IN_PROGRESS, PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.EXPIRED -> throw ApiFailure(409, "INVALID_STATE")
        }
    }
    private fun audit(id: UUID, action: String, version: Int) {
        execute(c, "INSERT INTO shopping_audit(id,plan_id,owner_id,action,version,occurred_at) VALUES (?,?,?,?,?,?)", UUID.randomUUID(), id, owner, action, version, clock.instant())
    }
    private fun advance(p: PlanRow, status: PlanStatus, action: String) {
        execute(c, "UPDATE shopping_plans SET version=version+1,status=?,current_finalized_revision=NULL,updated_at=? WHERE id=? AND owner_id=?",
            status.name, clock.instant(), p.id, owner)
        execute(c, "UPDATE shopping_revision_validity SET status='INVALIDATED' WHERE plan_id=? AND status='CURRENT'", p.id)
        execute(c, "UPDATE shopping_artifacts SET status='INVALIDATED' WHERE plan_id=? AND input_version<=? AND status NOT IN ('FAILED','COMPLETED','INVALIDATED')", p.id, p.version)
        audit(p.id, action, p.version + 1)
    }
    private fun validateSelections(values: List<Selection>, maxStores: Int): List<Selection> {
        if (values.isEmpty() || values.size > maxStores) invalid("selections", "STORE_LIMIT_EXCEEDED")
        val resolved = values.map { selection ->
            if (selection.retailer_id == null && selection.store_id == null) invalid("selections", "PLACE_REQUIRED")
            val store = selection.store_id?.let(::store)
            if (store != null && selection.retailer_id != null && selection.retailer_id != store.retailer_id) invalid("selections", "INVALID_STORE_RELATION")
            val retailer = selection.retailer_id ?: store?.retailer_id
            retailer?.let { id -> query(c, "SELECT 1 FROM shopping_retailers WHERE id=? AND status='ACTIVE'", id).use { s ->
                s.executeQuery().use { if (!it.next()) invalid("selections", "UNKNOWN_REFERENCE") }
            } }
            Selection(retailer, selection.store_id)
        }
        val stores = resolved.mapNotNull { it.store_id }
        if (resolved.distinct().size != resolved.size || stores.distinct().size != stores.size) invalid("selections", "DUPLICATE_STORE")
        // A retailer-wide selection already includes its branches; avoid ambiguous duplicate context.
        val retailerOnly = resolved.filter { it.store_id == null }.mapNotNull { it.retailer_id }
        if (retailerOnly.any { id -> resolved.count { it.retailer_id == id } > 1 }) invalid("selections", "DUPLICATE_STORE")
        return resolved
    }
    private fun validateCategories(values: List<String>) {
        if (values.isEmpty() || values.distinct().size != values.size) invalid("category_ids", "INVALID_SELECTION")
        values.forEach { id -> query(c, "SELECT 1 FROM shopping_categories WHERE id=? AND status='ACTIVE'", id).use { s ->
            s.executeQuery().use { if (!it.next()) invalid("category_ids", "UNKNOWN_REFERENCE") }
        } }
    }
    fun createDraft(input: DraftInput): PlanView {
        val budget = money.amount(input.budget, input.currency, "budget", positive = true)
        val profile = query(c, "SELECT max_stores,optimization_mode FROM profiles WHERE user_id=?", owner).use { s -> s.executeQuery().use { r ->
            if (r.next()) r.getInt(1) to r.getString(2) else 3 to "BALANCED"
        } }
        val resolved = validateSelections(input.selections, profile.first)
        validateCategories(input.category_ids)
        val id = UUID.randomUUID()
        val now = clock.instant()
        execute(c, "INSERT INTO shopping_plans(id,owner_id,currency,budget,max_stores,selected_mode,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
            id, owner, input.currency, budget, profile.first, profile.second, now, now)
        resolved.forEachIndexed { index, selection -> execute(c, "INSERT INTO shopping_plan_selections(plan_id,position,retailer_id,store_id) VALUES (?,?,?,?)", id, index, selection.retailer_id, selection.store_id) }
        input.category_ids.forEach { execute(c, "INSERT INTO shopping_plan_categories(plan_id,category_id) VALUES (?,?)", id, it) }
        audit(id, "DRAFT_CREATED", 1)
        return plan(id)
    }
    /** All edited inputs, invalidation and the one version advance commit atomically. */
    fun updatePlan(id: UUID, expected: Int, budget: String?, newSelections: List<Selection>?, categoryIds: List<String>?): PlanView {
        val p = row(id); expect(p, expected); editable(p)
        val nextBudget = budget?.let { money.amount(it, p.currency, "budget", positive = true) } ?: p.budget.toBigDecimal()
        val oldSelections = selections(id)
        val oldCategories = categories(id)
        val nextSelections = newSelections?.let { validateSelections(it, p.maxStores) } ?: oldSelections
        categoryIds?.let(::validateCategories)
        val nextCategories = categoryIds?.sorted() ?: oldCategories
        val contextChanged = nextSelections != oldSelections || nextCategories != oldCategories
        if (!contextChanged && nextBudget.compareTo(p.budget.toBigDecimal()) == 0) return plan(id)
        execute(c, "UPDATE shopping_plans SET budget=? WHERE id=?", nextBudget, id)
        if (contextChanged) {
            execute(c, "DELETE FROM shopping_plan_selections WHERE plan_id=?", id)
            nextSelections.forEachIndexed { index, selection -> execute(c,
                "INSERT INTO shopping_plan_selections(plan_id,position,retailer_id,store_id) VALUES (?,?,?,?)",
                id, index, selection.retailer_id, selection.store_id) }
            execute(c, "DELETE FROM shopping_plan_categories WHERE plan_id=?", id)
            nextCategories.forEach { execute(c, "INSERT INTO shopping_plan_categories(plan_id,category_id) VALUES (?,?)", id, it) }
            execute(c, "UPDATE shopping_plans SET catalog_status='FAILED' WHERE id=?", id)
        }
        val hasItems = records(id, p.currency).isNotEmpty()
        val nextStatus = if (contextChanged) { if (hasItems) PlanStatus.BUILDING_LIST else PlanStatus.DRAFT } else p.status
        advance(p, nextStatus, "PLAN_INPUTS_CHANGED")
        val result = plan(id)
        return if (contextChanged) result.copy(warnings = result.warnings + ShoppingWarning("PLAN_CONTEXT_CHANGED",
            "Nơi mua hoặc nhóm hàng đã đổi. Cần tải lại catalog và kiểm tra các món đã chọn.", result.items.map { it.id })) else result
    }
    /** Catalog adapters will call this after their I/O has finished, never from inside a provider transaction. */
    fun catalogState(id: UUID, expected: Int, state: CatalogStatus): PlanView {
        val p = row(id); expect(p, expected)
        if (p.status !in setOf(PlanStatus.DRAFT, PlanStatus.LOADING_PRODUCTS, PlanStatus.BROWSING_PRODUCTS, PlanStatus.BUILDING_LIST)) throw ApiFailure(409, "INVALID_STATE")
        if (p.catalog == state) return plan(id)
        if (state != CatalogStatus.LOADING && p.catalog != CatalogStatus.LOADING) throw ApiFailure(409, "INVALID_STATE")
        val hasItems = records(id, p.currency).isNotEmpty()
        val status = when (state) {
            CatalogStatus.LOADING -> if (hasItems) PlanStatus.BUILDING_LIST else PlanStatus.LOADING_PRODUCTS
            CatalogStatus.READY, CatalogStatus.PARTIAL, CatalogStatus.EMPTY -> if (hasItems) PlanStatus.BUILDING_LIST else PlanStatus.BROWSING_PRODUCTS
            CatalogStatus.FAILED -> if (hasItems) PlanStatus.BUILDING_LIST else PlanStatus.DRAFT
        }
        advance(p, status, "CATALOG_STATE_CHANGED")
        execute(c, "UPDATE shopping_plans SET catalog_status=? WHERE id=?", state.name, id)
        return plan(id)
    }
    fun changeBudget(id: UUID, expected: Int, budget: String): PlanView {
        val p = row(id); expect(p, expected); editable(p)
        val value = money.amount(budget, p.currency, "budget", positive = true)
        if (value.compareTo(p.budget.toBigDecimal()) == 0) return plan(id)
        execute(c, "UPDATE shopping_plans SET budget=? WHERE id=?", value, id)
        advance(p, p.status, "BUDGET_CHANGED")
        return plan(id)
    }
    fun addItem(id: UUID, expected: Int, input: ItemInput): PlanView {
        val p = row(id); expect(p, expected); editable(p)
        if (p.status == PlanStatus.DRAFT) throw ApiFailure(409, "INVALID_STATE")
        if ((input.offer_ref == null) == input.custom_name.isNullOrBlank()) invalid("offer_ref", "CHOOSE_OFFER_OR_CUSTOM_NAME")
        val selected = input.offer_ref?.let(::selected)
        val offer = selected?.offer
        if (selected != null && offer != null) {
            if (offer.version != input.expected_offer_version) throw ApiFailure(409, "PRICE_CHANGED")
            if (offer.stock_status == StockStatus.OUT_OF_STOCK) throw ApiFailure(409, "OFFER_UNAVAILABLE")
            if (offer.currency != p.currency) invalid("currency", "CURRENCY_MISMATCH")
            val inContext = selections(id).any { selection ->
                if (selection.store_id != null) selection.store_id == offer.store_id
                else selection.retailer_id != null && selection.retailer_id == offer.retailer_id
            }
            if (!inContext || selected.category !in categories(id)) invalid("offer_ref", "OUTSIDE_PLAN_CONTEXT")
            if (offer.price != null) {
                money.amount(offer.price, p.currency)
                if (offer.source.isNullOrBlank() || offer.observed_at == null || offer.freshness == Freshness.UNKNOWN) throw ApiFailure(422, "OFFER_METADATA_INCOMPLETE")
                val observed = try { Instant.parse(offer.observed_at) } catch (_: java.time.format.DateTimeParseException) { throw ApiFailure(422, "OFFER_METADATA_INCOMPLETE") }
                if (observed > clock.instant()) throw ApiFailure(422, "OFFER_METADATA_INCOMPLETE")
            }
        }
        val unit = selected?.unit ?: input.unit?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("unit", "REQUIRED")
        if (selected != null && input.unit != null && input.unit != unit) invalid("unit", "UNIT_MISMATCH")
        val step = selected?.step ?: customQuantitySteps[unit] ?: "1"
        val quantity = money.quantity(input.quantity, step)
        val productId = selected?.product?.id
        val customName = if (productId == null) selected?.customName ?: input.custom_name!!.trim() else null
        val duplicate = query(c, "SELECT id FROM shopping_items WHERE plan_id=? AND ((?::text IS NOT NULL AND product_id=?) OR (?::text IS NULL AND lower(custom_name)=lower(?) AND unit=?))",
            id, productId, productId, productId, customName, unit).use { s -> s.executeQuery().use { r -> if (r.next()) r.uuid("id") else null } }
        if (duplicate != null) throw ApiFailure(409, "DUPLICATE_ITEM", details = buildJsonObject { put("item_id", duplicate.toString()) })
        execute(c, """INSERT INTO shopping_items(id,plan_id,product_id,custom_name,offer_kind,offer_id,quantity,quantity_step,unit,priority,
            product_snapshot,store_snapshot,selected_price_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)""",
            UUID.randomUUID(), id, productId, customName, offer?.ref?.kind?.name, offer?.ref?.id, quantity, step.toBigDecimal(), unit, input.priority.name,
            selected?.product?.let { shoppingJson.encodeToString(it) }, selected?.store?.let { shoppingJson.encodeToString(it) }, offer?.let { shoppingJson.encodeToString(it) })
        advance(p, PlanStatus.BUILDING_LIST, "ITEM_ADDED")
        return plan(id)
    }
    fun changeItem(id: UUID, itemId: UUID, expected: Int, quantity: String? = null, priority: Priority? = null): PlanView {
        val p = row(id); expect(p, expected); editable(p)
        val record = records(id, p.currency).find { it.item.id == itemId.toString() } ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
        val nextQuantity = money.quantity(quantity ?: record.item.quantity, record.step)
        val nextPriority = priority ?: record.item.priority
        if (nextQuantity.compareTo(record.item.quantity.toBigDecimal()) == 0 && nextPriority == record.item.priority) return plan(id)
        execute(c, "UPDATE shopping_items SET quantity=?,priority=? WHERE plan_id=? AND id=?", nextQuantity, nextPriority.name, id, itemId)
        advance(p, PlanStatus.BUILDING_LIST, "ITEM_CHANGED")
        return plan(id)
    }
    fun removeItem(id: UUID, itemId: UUID, expected: Int, confirmEssential: Boolean = false): PlanView {
        val p = row(id); expect(p, expected); editable(p)
        val item = records(id, p.currency).find { it.item.id == itemId.toString() }?.item ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
        if (item.priority == Priority.ESSENTIAL && !confirmEssential) throw ApiFailure(409, "ESSENTIAL_CONFIRMATION_REQUIRED")
        execute(c, "DELETE FROM shopping_items WHERE plan_id=? AND id=?", id, itemId)
        advance(p, PlanStatus.BUILDING_LIST, "ITEM_REMOVED")
        return plan(id)
    }
    /** Persistence primitive after a future Finalize service has validated review/price/consent in this transaction. */
    fun appendRevision(id: UUID, expected: Int): RevisionSnapshot {
        val p = row(id); expect(p, expected)
        if (p.status !in setOf(PlanStatus.BUILDING_LIST, PlanStatus.COMPARING, PlanStatus.OPTIMIZING)) throw ApiFailure(409, "INVALID_STATE")
        val records = records(id, p.currency)
        if (records.isEmpty()) throw ApiFailure(422, "EMPTY_LIST")
        val now = clock.instant()
        val view = plan(id)
        val finalized = view.plan.copy(version = p.version + 1, revision = p.revision + 1, status = PlanStatus.FINALIZED, updated_at = now.toString())
        val stores = (records.mapNotNull { it.store } + selections(id).mapNotNull { it.store_id?.let(::store) }).distinctBy { it.id }
        val snapshot = RevisionSnapshot(finalized, records.map { it.item }, records.mapNotNull { it.product }.distinctBy { it.id }, stores,
            records.map { r -> Allocation(r.item.id, r.item.offer_ref, r.store?.id, r.item.selected_price_snapshot?.retailer_id,
                r.item.quantity, r.item.selected_price_snapshot, r.item.subtotal) }, now.toString())
        execute(c, "INSERT INTO shopping_plan_revisions(plan_id,revision,finalized_at,payload) VALUES (?,?,?,?::jsonb)", id, finalized.revision, now, shoppingJson.encodeToString(snapshot))
        advance(p, PlanStatus.BUILDING_LIST, "REVISION_APPENDED")
        execute(c, "UPDATE shopping_plans SET revision=?,current_finalized_revision=?,status='FINALIZED' WHERE id=?", finalized.revision, finalized.revision, id)
        execute(c, "INSERT INTO shopping_revision_validity(plan_id,revision,status) VALUES (?,?,'CURRENT')", id, finalized.revision)
        return snapshot
    }
    fun reopen(id: UUID, expected: Int): PlanView {
        val p = row(id); expect(p, expected)
        if (p.status != PlanStatus.FINALIZED) throw ApiFailure(409, "INVALID_STATE")
        advance(p, PlanStatus.BUILDING_LIST, "PLAN_REOPENED")
        return plan(id)
    }
    /** Dependency marker only; no optimization/routing provider is executed here. */
    fun registerArtifact(id: UUID, expected: Int, kind: String, status: String): UUID {
        val p = row(id); expect(p, expected)
        if (kind !in setOf("SUGGESTION", "OPTIMIZATION", "ROUTE")) invalid("kind", "INVALID_ENUM")
        if (status !in setOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "READY", "ACTIVE", "COMPLETED")) invalid("status", "INVALID_ENUM")
        val artifactId = UUID.randomUUID()
        execute(c, "INSERT INTO shopping_artifacts(id,plan_id,input_version,kind,status) VALUES (?,?,?,?,?)", artifactId, id, p.version, kind, status)
        return artifactId
    }
}
