package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import org.json.JSONObject

data class CatalogFilter(val retailer: String? = null, val category: String? = null, val brand: String? = null, val query: String = "", val sort: String = "RELEVANCE")
data class RetailerCatalogResult(val id: String, val status: String, val errorCode: String?, val retrySeconds: Int?)
data class CatalogPage(val items: List<Offer>, val nextCursor: String?, val status: String, val retailers: List<RetailerCatalogResult>, val warnings: List<ShoppingWarning>)
data class CatalogDetail(val product: Product, val offers: List<Offer>, val warnings: List<ShoppingWarning>)
data class CatalogRefreshIntent(val key: String, val body: String)

interface CatalogGateway {
    suspend fun catalog(planId: String, filter: CatalogFilter, cursor: String?): CatalogPage = throw AuthFailure.Unconfigured()
    suspend fun refreshCatalog(planId: String, intent: CatalogRefreshIntent): ShoppingPlan = throw AuthFailure.Unconfigured()
    suspend fun productDetail(planId: String, productId: String): CatalogDetail = throw AuthFailure.Unconfigured()
}

object CatalogDecoders {
    private val statuses = setOf("LOADING","READY","PARTIAL","EMPTY","FAILED")
    fun page(raw: String): CatalogPage = decode {
        val data=JSONObject(raw).getJSONObject("data")
        val items=data.getJSONArray("items")
        val retailers=data.getJSONArray("retailer_results")
        CatalogPage((0 until items.length()).map { ShoppingContracts.offer(items.getJSONObject(it)) },optional(data,"next_cursor"),
            data.getString("status").also { require(it in statuses) },(0 until retailers.length()).map { retailers.getJSONObject(it).let { r ->
                RetailerCatalogResult(r.getString("retailer_id"),r.getString("status").also { require(it in statuses) },optional(r,"error_code"),
                    if (!r.has("retry_after_seconds") || r.isNull("retry_after_seconds")) null else r.getInt("retry_after_seconds").also { require(it>=0) })
            } },warnings(data))
    }
    fun detail(raw: String): CatalogDetail = decode {
        val data=JSONObject(raw).getJSONObject("data");val array=data.getJSONArray("offers")
        val offers=(0 until array.length()).map { ShoppingContracts.offer(array.getJSONObject(it)) }
        val product=offers.firstOrNull()?.product ?: throw IllegalArgumentException()
        require(data.getJSONObject("product").getString("id")==product.id && offers.all { it.product?.id==product.id })
        CatalogDetail(product,offers,warnings(data))
    }
    fun refreshed(raw: String): ShoppingPlan = decode { ShoppingContracts.decodePlan(JSONObject(raw).getJSONObject("data").getJSONObject("plan").toString()) }
    private fun optional(obj: JSONObject,key: String): String? = if (obj.isNull(key)) null else obj.get(key) as? String ?: throw IllegalArgumentException()
    private fun warnings(obj: JSONObject): List<ShoppingWarning> {
        val array=obj.getJSONArray("warnings")
        return (0 until array.length()).map { array.getJSONObject(it).let { w ->
            val ids=w.getJSONArray("affected_ids");ShoppingWarning(w.getString("code"),w.getString("message"),(0 until ids.length()).map(ids::getString))
        } }
    }
    private fun <T> decode(block: ()->T): T = try { block() } catch (_: Exception) { throw AuthFailure.InvalidResponse() }
}
