package com.toked.smartshopping.backend.shopping

import kotlinx.serialization.Serializable

@Serializable enum class PlanStatus { DRAFT, LOADING_PRODUCTS, BROWSING_PRODUCTS, BUILDING_LIST, COMPARING, OPTIMIZING, FINALIZED, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED }
@Serializable enum class Priority { ESSENTIAL, RECOMMENDED, OPTIONAL }
@Serializable enum class OfferKind { CATALOG, PRIVATE }
@Serializable enum class PriceScope { RETAILER, STORE, USER_ENTERED }
@Serializable enum class Freshness { FRESH, STALE, EXPIRED, UNKNOWN }
@Serializable enum class StockStatus { IN_STOCK, OUT_OF_STOCK, UNKNOWN }
@Serializable enum class CatalogStatus { LOADING, READY, PARTIAL, EMPTY, FAILED }
@Serializable enum class ItemOutcome { PENDING, PURCHASED, REMOVED_BY_USER, REPLACED, SKIPPED_AT_COMPLETION }
@Serializable enum class ItemOrigin { INITIAL_PLAN, ADDED_DURING_SHOPPING, REPLACEMENT }
@Serializable data class ShoppingWarning(val code: String, val message: String, val affected_ids: List<String> = emptyList())
@Serializable data class Selection(val retailer_id: String? = null, val store_id: String? = null)
@Serializable data class OfferRef(val kind: OfferKind, val id: String)
@Serializable data class Product(
    val id: String, val name: String, val category_id: String, val unit: String, val quantity_step: String,
    val brand_id: String? = null, val barcode: String? = null, val image_url: String? = null, val package_size: String? = null,
    val brand_name: String? = null, val package_label: String? = null,
)
@Serializable data class Coordinate(val latitude: Double, val longitude: Double)
@Serializable data class StoreSnapshot(
    val id: String, val version: Int, val retailer_id: String?, val name: String, val address: String?,
    val location: Coordinate, val source: String, val visibility: String, val can_edit: Boolean = false,
)
@Serializable data class Offer(
    val ref: OfferRef, val version: String, val product: Product? = null, val store_product_id: String? = null,
    val retailer_id: String? = null, val store_id: String? = null, val price: String? = null, val currency: String,
    val source: String? = null, val observed_at: String? = null, val price_scope: PriceScope,
    val freshness: Freshness = Freshness.UNKNOWN, val stock_status: StockStatus = StockStatus.UNKNOWN,
    val confidence: Double? = null, val list_price: String? = null, val promotion: String? = null,
    val unit_price: String? = null, val warnings: List<ShoppingWarning> = emptyList(),
)
@Serializable data class BudgetSummary(
    val currency: String, val budget: String, val selected_total: String, val remaining_budget: String,
    val is_complete: Boolean, val missing_price_item_ids: List<String>, val over_budget_amount: String,
    val warnings: List<ShoppingWarning> = emptyList(),
)
@Serializable data class ShoppingItem(
    val id: String, val product_id: String?, val custom_name: String?, val offer_ref: OfferRef?,
    val quantity: String, val unit: String, val priority: Priority, val selected_price_snapshot: Offer?, val subtotal: String?,
)
@Serializable data class Plan(
    val id: String, val version: Int, val revision: Int, val status: PlanStatus, val currency: String, val budget: String,
    val selections: List<Selection>, val category_ids: List<String>, val max_stores: Int,
    val budget_summary: BudgetSummary, val catalog_status: CatalogStatus, val created_at: String, val updated_at: String,
)
@Serializable data class PlanView(
    val plan: Plan, val items: List<ShoppingItem>, val current_finalized_revision: Int?,
    val active_route_id: String? = null, val warnings: List<ShoppingWarning> = emptyList(),
)
@Serializable data class Allocation(
    val item_id: String, val offer_ref: OfferRef?, val store_id: String?, val retailer_id: String?,
    val quantity: String, val price_snapshot: Offer?, val subtotal: String?,
)
/** Persistence payload, distinct from the future FinalizedPlan HTTP result. Never hydrate from live catalog on read. */
@Serializable data class RevisionSnapshot(
    val plan: Plan, val items: List<ShoppingItem>, val products: List<Product>, val stores: List<StoreSnapshot>,
    val allocations: List<Allocation>, val finalized_at: String,
)
@Serializable data class SessionItemSnapshot(
    val id: String, val source_item_id: String?, val product_id: String?, val product_snapshot: Product?, val custom_name: String?,
    val category_id: String?, val offer_ref: OfferRef?, val retailer_id: String?, val store_id: String?,
    val store_snapshot: StoreSnapshot?, val quantity: String, val unit: String, val priority: Priority,
    val selected_price_snapshot: Offer?, val status: ItemOutcome, val origin: ItemOrigin, val replaces_session_item_id: String? = null,
)
@Serializable data class DraftInput(val budget: String, val currency: String, val selections: List<Selection>, val category_ids: List<String>)
@Serializable data class HistorySnapshot(
    val id: String, val session_id: String, val plan_id: String, val plan_revision: Int,
    val initial_items: List<SessionItemSnapshot>, val final_items: List<SessionItemSnapshot>,
    val final_purchased_total: String, val is_complete: Boolean, val missing_purchased_price_item_ids: List<String>,
    val currency: String, val completed_at: String, val warnings: List<ShoppingWarning>,
)
@Serializable data class ItemInput(
    val quantity: String, val offer_ref: OfferRef? = null, val expected_offer_version: String? = null,
    val custom_name: String? = null, val unit: String? = null, val priority: Priority = Priority.RECOMMENDED,
)
