package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlanReference(val id: String, val name: String, val status: String, val retailerIds:List<String> = emptyList())
data class PlanReferencePage(val items: List<PlanReference>, val nextCursor: String?)
data class PlanPage(val items: List<ShoppingPlan>, val nextCursor: String?)
data class PlanStore(val id: String, val retailerId: String?, val name: String, val address: String?, val private: Boolean)
data class PlanStorePage(val items: List<PlanStore>, val nextCursor: String?, val warnings: List<String>, val attribution: List<String>)
data class PlanMutation(val key: String, val body: String, val planId: String? = null)

interface PlanGateway {
    suspend fun planMaxStores(): Int
    suspend fun planReferences(kind: String, cursor: String?): PlanReferencePage
    suspend fun planStores(retailerId: String?, query: String, cursor: String?): PlanStorePage
    suspend fun planStore(id: String): PlanStore
    suspend fun plans(cursor: String?): PlanPage
    suspend fun plan(id: String): ShoppingPlanView
    suspend fun savePlan(mutation: PlanMutation): ShoppingPlanView
}

data class PlanDraft(val budget: String = "", val currency: String = "VND", val selections: List<Selection> = emptyList(),
    val categoryIds: List<String> = emptyList()) {
    fun budgetError(): String? {
        val value = budget.trim()
        if (value.length !in 1..128 || !value.matches(Regex("[0-9]+"))) return "Nhập số tiền, ví dụ 500000; không dùng dấu phân cách hàng nghìn."
        val amount = value.toBigDecimal()
        if (amount.signum() <= 0) return "Ngân sách phải lớn hơn 0."
        if (currency != "VND" || amount.stripTrailingZeros().scale() > 0) return "Ngân sách VND phải là số tiền nguyên."
        return null
    }
    fun errors(maxStores: Int?): Map<String, String> = buildMap {
        budgetError()?.let { put("budget", it) }
        if (maxStores == null) put("selections", "Chưa tải được giới hạn cửa hàng. Hãy thử lại.")
        else if (selections.isEmpty()) put("selections", "Chọn ít nhất một chuỗi hoặc cửa hàng.")
        else if (selections.size > maxStores) put("selections", "Chỉ chọn tối đa $maxStores nơi mua.")
        if (categoryIds.isEmpty()) put("category_ids", "Chọn ít nhất một nhóm hàng.")
    }
    fun body(expectedVersion: Int? = null): String = JSONObject().apply {
        if (expectedVersion != null) put("expected_version", expectedVersion) else put("currency", currency)
        put("budget", budget.trim().toBigDecimal().stripTrailingZeros().toPlainString())
        put("selections", JSONArray(selections.map { JSONObject().put("retailer_id", it.retailerId ?: JSONObject.NULL)
            .put("store_id", it.storeId ?: JSONObject.NULL) }))
        put("category_ids", JSONArray(categoryIds.sorted()))
    }.toString()
    companion object {
        fun from(plan: ShoppingPlan) = PlanDraft(plan.budget.amount.toPlainString(), plan.budget.currency, plan.selections, plan.categoryIds)
    }
}

internal object PlanDecoders {
    private fun data(raw: String) = JSONObject(raw).getJSONObject("data")
    private fun cursor(d: JSONObject): String? = if (d.isNull("next_cursor")) null else d.getString("next_cursor")
    private inline fun <T> decode(block: () -> T): T = try { block() }
        catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
        catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
        catch (_: InvalidShoppingResponse) { throw AuthFailure.InvalidResponse() }
    fun references(raw: String): PlanReferencePage = decode {
        val d = data(raw); val a = d.getJSONArray("items")
        PlanReferencePage((0 until a.length()).map { a.getJSONObject(it).let { p ->
            PlanReference(p.getString("id"), p.getString("name"), p.getString("status"),p.optJSONArray("retailer_ids")?.let {a->(0 until a.length()).map(a::getString)} ?: emptyList()) } }, cursor(d))
    }
    fun page(raw: String): PlanPage = decode {
        val d = data(raw); val a = d.getJSONArray("items")
        PlanPage((0 until a.length()).map { ShoppingContracts.decodePlan(a.getJSONObject(it).toString()) }, cursor(d))
    }
    fun view(raw: String): ShoppingPlanView = decode { ShoppingContracts.decodePlanView(data(raw).toString()) }
    fun store(raw: String): PlanStore = decode { storeObject(data(raw)) }
    private fun storeObject(s: JSONObject) = PlanStore(s.getString("id"), if (s.isNull("retailer_id")) null else s.getString("retailer_id"),
        s.getString("name"), if (s.isNull("address")) null else s.getString("address"), s.getString("visibility") == "PRIVATE")
    fun stores(raw: String): PlanStorePage = decode {
        val d = data(raw); val m = d.getJSONObject("map"); val a = m.getJSONArray("stores")
        require(m.getString("status") in setOf("READY", "PARTIAL", "EMPTY"))
        val warnings = m.getJSONArray("warnings"); val attribution = m.getJSONArray("attribution")
        PlanStorePage((0 until a.length()).map { storeObject(a.getJSONObject(it)) }, cursor(d),
            (0 until warnings.length()).map { warnings.getJSONObject(it).getString("message") },
            (0 until attribution.length()).map { attribution.getJSONObject(it).getString("text") })
    }
}

fun planMoney(value: String, currency: String = "VND"): String = try {
    "${NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(BigDecimal(value))} ${if (currency == "VND") "₫" else currency}"
} catch (_: NumberFormatException) { "—" }
fun planTitle(plan: ShoppingPlan): String = "Kế hoạch ${DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(plan.createdAt))}"
fun planStatus(state: PlanState): String = when (state) {
    PlanState.DRAFT -> "Bản nháp"; PlanState.LOADING_PRODUCTS -> "Đang tải sản phẩm"; PlanState.BROWSING_PRODUCTS -> "Đang chọn sản phẩm"
    PlanState.BUILDING_LIST -> "Đang lập danh sách"; PlanState.COMPARING -> "Đang so sánh"; PlanState.OPTIMIZING -> "Đang tối ưu"
    PlanState.FINALIZED -> "Đã chốt"; PlanState.IN_PROGRESS -> "Đang mua sắm"; PlanState.COMPLETED -> "Đã hoàn tất"
    PlanState.CANCELLED -> "Đã hủy"; PlanState.EXPIRED -> "Đã hết hạn"
}
