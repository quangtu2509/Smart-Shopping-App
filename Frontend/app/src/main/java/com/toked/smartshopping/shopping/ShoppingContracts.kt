package com.toked.smartshopping.shopping

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

data class Money(val currency: String, val amount: BigDecimal)
@JvmInline value class Quantity private constructor(val value: String) {
    fun decimal(): BigDecimal = value.toBigDecimal()
    companion object {
        fun parse(value: String): Quantity {
            require(value.length <= 128 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) && value.toBigDecimal().signum()>0)
            return Quantity(value)
        }
    }
}
enum class PlanState { DRAFT, LOADING_PRODUCTS, BROWSING_PRODUCTS, BUILDING_LIST, COMPARING, OPTIMIZING, FINALIZED, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED }
data class ShoppingWarning(val code: String, val message: String, val affectedIds: List<String>)
data class Selection(val retailerId: String?, val storeId: String?)
data class OfferRef(val kind: String, val id: String)
data class Product(val id: String, val name: String, val categoryId: String, val unit: String, val quantityStep: Quantity,
    val brandId: String?, val barcode: String?, val imageUrl: String?, val packageSize: BigDecimal?, val brandName:String?=null, val packageLabel:String?=null)
data class Offer(val ref: OfferRef, val version: String, val product: Product?, val storeProductId: String?, val retailerId: String?,
    val storeId: String?, val currency: String, val price: Money?, val source: String?, val observedAt: String?, val priceScope: String,
    val freshness: String, val stockStatus: String, val confidence: Double?, val listPrice: Money?, val promotion: String?, val unitPrice: Money?,
    val warnings: List<ShoppingWarning>)
data class BudgetSummary(val budget: Money, val selectedTotal: Money, val remainingBudget: Money, val complete: Boolean,
    val missingPriceItemIds: List<String>, val overBudgetAmount: Money, val warnings: List<ShoppingWarning>)
data class ShoppingItem(val id: String, val productId: String?, val customName: String?, val offerRef: OfferRef?, val quantity: Quantity,
    val unit: String, val priority: String, val selectedPriceSnapshot: Offer?, val subtotal: Money?)
data class ShoppingPlan(val id: String, val version: Int, val revision: Int, val status: PlanState, val budget: Money,
    val selections: List<Selection>, val categoryIds: List<String>, val maxStores: Int, val budgetSummary: BudgetSummary,
    val catalogStatus: String, val createdAt: String, val updatedAt: String)
data class ShoppingPlanView(val plan: ShoppingPlan, val items: List<ShoppingItem>, val currentFinalizedRevision: Int?,
    val activeRouteId: String?, val warnings: List<ShoppingWarning>)
class InvalidShoppingResponse : Exception("Invalid shopping response")

/** Decodes server totals; Android never replaces them with a separate budget calculation. */
object ShoppingContracts {
    fun decodePlanView(raw: String): ShoppingPlanView = try {
        val data = JSONObject(raw)
        val p = data.getJSONObject("plan")
        val plan = decodePlanObject(p)
        val currency = plan.budget.currency
        val revision = plan.revision
        val summary = plan.budgetSummary
        val items = array(data,"items") { item ->
            require(item.has("selected_price_snapshot") && item.has("offer_ref") && item.has("subtotal"))
            val snapshot = if (item.isNull("selected_price_snapshot")) null else offer(item.getJSONObject("selected_price_snapshot"))
            require(snapshot==null || snapshot.currency==currency)
            val ref = if (item.isNull("offer_ref")) null else ref(item.getJSONObject("offer_ref"))
            require(ref==snapshot?.ref)
            val subtotal = if (item.isNull("subtotal")) null else money(item,"subtotal",currency)
            require((snapshot?.price==null)==(subtotal==null))
            val productId=optional(item,"product_id");val custom=optional(item,"custom_name")
            require((productId==null)!=(custom==null))
            ShoppingItem(text(item,"id"),productId,custom,ref,Quantity.parse(text(item,"quantity")),text(item,"unit"),
                text(item,"priority").also { require(it in setOf("ESSENTIAL","RECOMMENDED","OPTIONAL")) },snapshot,subtotal)
        }
        require(items.map { it.id }.distinct().size==items.size)
        require(items.filter { it.subtotal==null }.map { it.id }.toSet()==summary.missingPriceItemIds.toSet())
        require(data.has("current_finalized_revision"))
        val current = if (data.isNull("current_finalized_revision")) null else integer(data,"current_finalized_revision").also { require(it>0 && it==revision) }
        ShoppingPlanView(plan,items,current,optional(data,"active_route_id"),warnings(data))
    } catch (_: JSONException) { throw InvalidShoppingResponse() }
      catch (_: IllegalArgumentException) { throw InvalidShoppingResponse() }
      catch (_: java.time.format.DateTimeParseException) { throw InvalidShoppingResponse() }

    fun decodePlan(raw: String): ShoppingPlan = try { decodePlanObject(JSONObject(raw)) }
        catch (_: JSONException) { throw InvalidShoppingResponse() }
        catch (_: IllegalArgumentException) { throw InvalidShoppingResponse() }
        catch (_: java.time.format.DateTimeParseException) { throw InvalidShoppingResponse() }

    private fun decodePlanObject(p: JSONObject): ShoppingPlan {
        val currency = text(p,"currency")
        val max = integer(p,"max_stores").also { require(it in 1..3) }
        val version = integer(p,"version").also { require(it>0) }
        val revision = integer(p,"revision").also { require(it>=0) }
        val budget = money(p,"budget",currency).also { require(it.amount.signum()>0) }
        val summary = summary(p.getJSONObject("budget_summary"),currency)
        require(summary.budget.amount.compareTo(budget.amount)==0)
        val selections = array(p,"selections") { s -> Selection(optional(s,"retailer_id"),optional(s,"store_id")).also { require(it.retailerId!=null || it.storeId!=null) } }
        require(selections.isNotEmpty() && selections.size<=max && selections.distinct().size==selections.size)
        val categories = strings(p.getJSONArray("category_ids")).also { require(it.isNotEmpty()) }
        return ShoppingPlan(text(p,"id"),version,revision,PlanState.valueOf(text(p,"status")),budget,selections,categories,max,summary,
            text(p,"catalog_status").also { require(it in setOf("LOADING","READY","PARTIAL","EMPTY","FAILED")) },timestamp(p,"created_at"),timestamp(p,"updated_at"))
    }

    private fun summary(json: JSONObject, currency: String): BudgetSummary {
        require(text(json,"currency")==currency)
        val missing=strings(json.getJSONArray("missing_price_item_ids"))
        val complete=json.get("is_complete") as? Boolean ?: throw JSONException("Expected boolean")
        require(complete==missing.isEmpty())
        return BudgetSummary(money(json,"budget",currency),money(json,"selected_total",currency),money(json,"remaining_budget",currency,true),
            complete,missing,money(json,"over_budget_amount",currency),warnings(json))
    }
    internal fun offer(json: JSONObject): Offer {
        require(json.has("product") && json.has("confidence"))
        val currency=text(json,"currency")
        val product=if (json.isNull("product")) null else json.getJSONObject("product").let { p ->
            Product(text(p,"id"),text(p,"name"),text(p,"category_id"),text(p,"unit"),Quantity.parse(text(p,"quantity_step")),
                optional(p,"brand_id"),optional(p,"barcode"),optional(p,"image_url"),optional(p,"package_size")?.let { decimal(it,false) },
                if(p.has("brand_name")) optional(p,"brand_name") else null, if(p.has("package_label")) optional(p,"package_label") else null)
        }
        fun optionalMoney(field: String): Money? { require(json.has(field)); return if (json.isNull(field)) null else money(json,field,currency) }
        return Offer(ref(json.getJSONObject("ref")),text(json,"version"),product,optional(json,"store_product_id"),optional(json,"retailer_id"),
            optional(json,"store_id"),currency,optionalMoney("price"),optional(json,"source"),optional(json,"observed_at")?.also { Instant.parse(it) },
            text(json,"price_scope").also { require(it in setOf("RETAILER","STORE","USER_ENTERED")) },
            text(json,"freshness").also { require(it in setOf("FRESH","STALE","EXPIRED","UNKNOWN")) },
            text(json,"stock_status").also { require(it in setOf("IN_STOCK","OUT_OF_STOCK","UNKNOWN")) },
            if(json.isNull("confidence")) null else json.getDouble("confidence").also { require(it in 0.0..1.0) },
            optionalMoney("list_price"),optional(json,"promotion"),optionalMoney("unit_price"),warnings(json))
    }
    private fun ref(json: JSONObject) = OfferRef(text(json,"kind").also { require(it in setOf("CATALOG","PRIVATE")) },text(json,"id"))
    private fun money(json: JSONObject, field: String, currency: String, signed: Boolean = false): Money {
        require(currency.matches(Regex("[A-Z]{3}")))
        return Money(currency,decimal(text(json,field),signed))
    }
    private fun decimal(value: String, signed: Boolean): BigDecimal {
        require(value.length<=128 && value.matches(Regex(if(signed) "-?[0-9]+(?:\\.[0-9]+)?" else "[0-9]+(?:\\.[0-9]+)?")))
        return value.toBigDecimal()
    }
    private fun text(json: JSONObject, field: String) = (json.get(field) as? String)?.also { require(it.isNotBlank()) } ?: throw JSONException("Expected string")
    private fun optional(json: JSONObject, field: String): String? { require(json.has(field)); return if(json.isNull(field)) null else text(json,field) }
    private fun integer(json: JSONObject, field: String): Int {
        val value=json.get(field)
        require(value is Int || value is Long)
        val number=(value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }
    private fun timestamp(json: JSONObject, field: String) = text(json,field).also { Instant.parse(it) }
    private fun strings(array: JSONArray) = (0 until array.length()).map { (array.get(it) as? String)?.also { s -> require(s.isNotBlank()) } ?: throw JSONException("Expected string") }
    private fun <T> array(json: JSONObject, field: String, block: (JSONObject)->T): List<T> = json.getJSONArray(field).let { a -> (0 until a.length()).map { block(a.getJSONObject(it)) } }
    private fun warnings(json: JSONObject) = array(json,"warnings") { ShoppingWarning(text(it,"code"),text(it,"message"),strings(it.getJSONArray("affected_ids"))) }
}
