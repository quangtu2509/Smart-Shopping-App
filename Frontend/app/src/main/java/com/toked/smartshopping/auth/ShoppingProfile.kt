package com.toked.smartshopping.auth

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ShoppingProfile(
    val version: Int = 1, val householdSize: Int? = null, val defaultPeriod: String? = null,
    val preferredCategories: List<String> = emptyList(), val avoidedCategories: List<String> = emptyList(),
    val preferredBrands: List<String> = emptyList(), val avoidedBrands: List<String> = emptyList(),
    val maxStores: Int = 3, val mode: String = "BALANCED",
) {
    companion object {
        fun decode(json: JSONObject): ShoppingProfile {
            fun ids(field: String): List<String> = json.getJSONArray(field).let { a -> (0 until a.length()).map { a.getString(it) } }
            return ShoppingProfile(json.getInt("version"), if (json.isNull("household_size")) null else json.getInt("household_size"),
                if (json.isNull("default_period")) null else json.getString("default_period"), ids("preferred_category_ids"),
                ids("avoided_category_ids"), ids("preferred_brand_ids"), ids("avoided_brand_ids"), json.getInt("max_stores"), json.getString("optimization_mode")).also {
                require(it.version > 0 && it.maxStores in 1..3 && it.mode in profileModes.keys)
                require(it.householdSize == null || it.householdSize > 0)
            }
        }
    }
}
val profileModes = linkedMapOf("BALANCED" to "Cân bằng", "CHEAPEST" to "Tiết kiệm nhất", "CONVENIENT" to "Thuận tiện nhất")
data class ProfileReference(val id: String, val name: String, val status: String = "ACTIVE")
data class ReferencePage(val items: List<ProfileReference>, val nextCursor: String?)
data class ProfileDraft(
    val household: String = "", val period: String = "",
    val preferredCategories: List<String> = emptyList(), val avoidedCategories: List<String> = emptyList(),
    val preferredBrands: List<String> = emptyList(), val avoidedBrands: List<String> = emptyList(),
    val maxStores: Int = 3, val mode: String = "BALANCED",
) {
    fun errors(): Map<String, String> = buildMap {
        if (household.isNotBlank() && (household.toIntOrNull()?.let { it > 0 } != true)) put("household_size", "Nhập số người nguyên lớn hơn 0 hoặc để trống.")
        if (period.isNotBlank() && wireShoppingPeriod(period) == null) put("default_period", "Nhập chu kỳ dương, ví dụ 2 tuần hoặc 1 tháng.")
        if (maxStores !in 1..3) put("max_stores", "Chọn từ 1 đến 3 cửa hàng.")
        if (mode !in profileModes) put("optimization_mode", "Chọn chế độ tối ưu hợp lệ.")
        if (preferredCategories.intersect(avoidedCategories.toSet()).isNotEmpty()) put("avoided_category_ids", "Một nhóm hàng không thể vừa ưu tiên vừa tránh.")
        if (preferredBrands.intersect(avoidedBrands.toSet()).isNotEmpty()) put("avoided_brand_ids", "Một thương hiệu không thể vừa ưu tiên vừa tránh.")
    }
    fun patch(version: Int): ProfilePatch = ProfilePatch(UUID.randomUUID().toString(), JSONObject()
        .put("expected_version", version).put("household_size", household.trim().toIntOrNull() ?: JSONObject.NULL)
        .put("default_period", wireShoppingPeriod(period) ?: JSONObject.NULL)
        .put("preferred_category_ids", JSONArray(preferredCategories)).put("avoided_category_ids", JSONArray(avoidedCategories))
        .put("preferred_brand_ids", JSONArray(preferredBrands)).put("avoided_brand_ids", JSONArray(avoidedBrands))
        .put("max_stores", maxStores).put("optimization_mode", mode).toString())
    companion object {
        fun from(profile: ShoppingProfile) = ProfileDraft(profile.householdSize?.toString().orEmpty(), displayShoppingPeriod(profile.defaultPeriod),
            profile.preferredCategories, profile.avoidedCategories, profile.preferredBrands, profile.avoidedBrands, profile.maxStores, profile.mode)
    }
}
class ProfilePatch(val key: String, val body: String)
data class ProfileUiState(
    val loading: Boolean = true, val saving: Boolean = false, val saved: ShoppingProfile? = null,
    val draft: ProfileDraft = ProfileDraft(), val categories: List<ProfileReference> = emptyList(), val brands: List<ProfileReference> = emptyList(),
    val referenceError: Boolean = false, val errors: Map<String, String> = emptyMap(), val message: String? = null,
    val referencesLoading: Boolean = false,
    val conflict: Boolean = false, val uncertainSave: Boolean = false, val confirmLeave: Boolean = false, val confirmReload: Boolean = false,
) {
    val dirty: Boolean get() = saved?.let { draft != ProfileDraft.from(it) } ?: false
    val editable: Boolean get() = !loading && !saving && saved != null && !uncertainSave
}
internal fun validShoppingPeriod(value: String): Boolean = try {
    val p = java.time.Period.parse(value)
    value.startsWith("P") && !p.isZero && !p.isNegative
} catch (_: java.time.format.DateTimeParseException) { false } catch (_: ArithmeticException) { false }

internal fun wireShoppingPeriod(input: String): String? {
    val value = input.trim()
    if (validShoppingPeriod(value)) return value
    val pattern = Regex("(\\d+)\\s*(năm|tháng|tuần|ngày)", RegexOption.IGNORE_CASE)
    val parts = pattern.findAll(value).toList()
    if (parts.isEmpty() || pattern.replace(value, "").replace(",", "").isNotBlank()) return null
    val units = parts.map { it.groupValues[2].lowercase(java.util.Locale.ROOT) }
    if (units.distinct().size != units.size) return null
    val counts = parts.map { it.groupValues[1].toIntOrNull() ?: return null }
    val values = units.zip(counts).toMap()
    val result = if (values.keys == setOf("tuần")) "P${values.getValue("tuần")}W"
    else try {
        java.time.Period.of(values["năm"] ?: 0, values["tháng"] ?: 0,
            Math.addExact(values["ngày"] ?: 0, Math.multiplyExact(values["tuần"] ?: 0, 7))).toString()
    } catch (_: ArithmeticException) { return null }
    return result.takeIf(::validShoppingPeriod)
}

internal fun displayShoppingPeriod(value: String?): String {
    if (value.isNullOrBlank()) return ""
    Regex("P(\\d+)W").matchEntire(value)?.let { return "${it.groupValues[1]} tuần" }
    return try {
        val p = java.time.Period.parse(value)
        listOf(p.years to "năm", p.months to "tháng", p.days to "ngày").filter { it.first != 0 }.joinToString(" ") { "${it.first} ${it.second}" }
    } catch (_: java.time.format.DateTimeParseException) { value }
}
