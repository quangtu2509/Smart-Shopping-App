package com.toked.smartshopping.backend.catalog

import com.toked.smartshopping.backend.shopping.*
import kotlinx.serialization.Serializable

@Serializable data class RetailerCatalogResult(val retailer_id: String, val status: CatalogStatus, val error_code: String? = null,
    val retry_after_seconds: Int? = null)
@Serializable data class CatalogPage(val items: List<Offer>, val next_cursor: String?, val status: CatalogStatus,
    val retailer_results: List<RetailerCatalogResult>, val warnings: List<ShoppingWarning>)
@Serializable data class ProductDetail(val product: Product, val offers: List<Offer>, val warnings: List<ShoppingWarning>)
@Serializable data class CatalogRefresh(val plan: Plan, val catalog_status: CatalogStatus = CatalogStatus.LOADING)
data class CatalogFilter(val retailer: String? = null, val store: String? = null, val category: String? = null,
    val query: String? = null, val brand: String? = null, val sort: String = "RELEVANCE", val limit: Int = 20, val cursor: String? = null)
data class CatalogTicket(val planId: String, val version: Int)
data class CatalogSyncResult(val received: Int, val imported: Int, val rejected: Int, val sources: Int, val partial: Boolean)
