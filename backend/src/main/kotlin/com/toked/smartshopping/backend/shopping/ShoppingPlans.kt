package com.toked.smartshopping.backend.shopping

import com.toked.smartshopping.backend.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.UUID

@Serializable data class StoreAttribution(val text: String, val url: String)
@Serializable data class StoredMapResult(val stores: List<StoreSnapshot>, val status: String,
    val attribution: List<StoreAttribution>, val warnings: List<ShoppingWarning>)
@Serializable data class StoredMapPage(val map: StoredMapResult, val next_cursor: String?)

internal class ShoppingCursor(private val scope: List<String?>, cursor: String?) {
    val after: String? = cursor?.let {
        if (it.length > 4096) throw ApiFailure(400, "INVALID_CURSOR")
        val values = try { shoppingJson.decodeFromString<List<String?>>(String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)) }
        catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
        if (values.size != scope.size + 1 || values.dropLast(1) != scope || values.last().isNullOrBlank()) throw ApiFailure(400, "INVALID_CURSOR")
        values.last()
    }
    fun next(id: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(shoppingJson.encodeToString(scope + id).toByteArray(Charsets.UTF_8))
}

/** HTTP-facing orchestration; provider work is deliberately outside the Draft transaction. */
class ShoppingPlans(db: Database, auth: AuthService) {
    private val data = ShoppingData(db, auth)
    fun get(access: String, id: String): PlanView = data.read(access) { plan(parseId(id)) }
    fun list(access: String, status: String?, limit: Int, cursor: String?): Page<Plan> = data.read(access) {
        plans(status, limit, cursor)
    }
    fun stores(access: String, bbox: String?, q: String?, retailers: String?, limit: Int, cursor: String?): StoredMapPage = data.read(access) {
        storedStores(bbox, q, retailers, limit, cursor)
    }
    fun store(access: String, id: String): StoreSnapshot = data.read(access) { store(id) }

    fun create(access: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("POST", "/plans", key.orEmpty(), body)) {
        allowed(body, setOf("budget", "currency", "selections", "category_ids"))
        val result = createDraft(DraftInput(string(body["budget"], "budget"), string(body["currency"], "currency"),
            selections(body["selections"]), ids(body["category_ids"])))
        MutationResult(201, shoppingJson.encodeToJsonElement(result), "/api/v1/plans/${result.plan.id}")
    }
    fun patch(access: String, id: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("PATCH", "/plans/$id", key.orEmpty(), body)) {
        allowed(body, setOf("expected_version"), setOf("budget", "selections", "category_ids"))
        val result = updatePlan(parseId(id), version(body), if ("budget" in body) string(body["budget"], "budget") else null,
            if ("selections" in body) selections(body["selections"]) else null,
            if ("category_ids" in body) ids(body["category_ids"]) else null)
        MutationResult(200, shoppingJson.encodeToJsonElement(result))
    }
    /** API-PLAN-05. A retry after success sees 404: the Plan no longer exists for its owner. */
    fun delete(access: String, id: String, key: String?, expected: String?): MutationResult = data.mutate(access,
        MutationRequest("DELETE", "/plans/$id", key.orEmpty(), JsonNull, buildJsonObject { put("expected_version", expected) })) {
        deletePlan(parseId(id), expected?.toIntOrNull()?.takeIf { it > 0 } ?: invalid("expected_version", "INVALID_INTEGER"))
        MutationResult(204)
    }
    /** API-LIST-02. */
    fun addItem(access: String, id: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("POST", "/plans/$id/items", key.orEmpty(), body)) {
        allowed(body, setOf("expected_version", "quantity"), setOf("offer_ref", "expected_offer_version", "custom_name", "unit", "priority"))
        val input = try { shoppingJson.decodeFromJsonElement<ItemInput>(JsonObject(body - "expected_version")) }
            catch (_: IllegalArgumentException) { invalid("item", "INVALID_ITEM") }
        MutationResult(201, shoppingJson.encodeToJsonElement(addItem(parseId(id), version(body), input)))
    }
    /** API-LIST-03. */
    fun changeItem(access: String, id: String, itemId: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("PATCH", "/plans/$id/items/$itemId", key.orEmpty(), body)) {
        allowed(body, setOf("expected_version"), setOf("quantity", "priority"))
        val priority = body["priority"]?.let { value -> string(value, "priority").let { p -> Priority.entries.firstOrNull { it.name == p } ?: invalid("priority", "INVALID_ENUM") } }
        MutationResult(200, shoppingJson.encodeToJsonElement(changeItem(parseId(id), parseId(itemId), version(body),
            body["quantity"]?.let { string(it, "quantity") }, priority)))
    }
    /** API-LIST-04. Essential items need confirm_essential=true. */
    fun removeItem(access: String, id: String, itemId: String, key: String?, expected: String?, confirmEssential: String?): MutationResult = data.mutate(access,
        MutationRequest("DELETE", "/plans/$id/items/$itemId", key.orEmpty(), JsonNull,
            buildJsonObject { put("expected_version", expected); put("confirm_essential", confirmEssential) })) {
        if (confirmEssential !in setOf(null, "true", "false")) invalid("confirm_essential", "INVALID_BOOLEAN")
        MutationResult(200, shoppingJson.encodeToJsonElement(removeItem(parseId(id), parseId(itemId),
            expected?.toIntOrNull()?.takeIf { it > 0 } ?: invalid("expected_version", "INVALID_INTEGER"), confirmEssential == "true")))
    }
    /** API-FINAL-01 for the current List; optimization options are not available yet. Reads only, so no idempotency record. */
    fun review(access: String, id: String, body: JsonObject): FinalizationReview = data.read(access) {
        allowed(body, setOf("expected_version"), setOf("optimization_option_id"))
        if (body["optimization_option_id"].let { it != null && it != JsonNull }) throw ApiFailure(422, "OPTIMIZATION_NOT_AVAILABLE")
        val current = row(parseId(id)).version
        if (current != version(body)) throw ApiFailure(409, "VERSION_CONFLICT", details = buildJsonObject { put("current_version", current) })
        reviewState(parseId(id), java.time.Instant.now()).review
    }
    /** API-FINAL-02. */
    fun finalize(access: String, id: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("POST", "/plans/$id/finalize", key.orEmpty(), body)) {
        allowed(body, setOf("expected_version", "review_id", "confirm_price_changes"), setOf("confirmed_essential_action_ids"))
        val confirm = (body["confirm_price_changes"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid("confirm_price_changes", "INVALID_BOOLEAN")
        MutationResult(200, shoppingJson.encodeToJsonElement(finalize(parseId(id), version(body), string(body["review_id"], "review_id"), confirm)))
    }
    /** API-FINAL-03. */
    fun reopen(access: String, id: String, key: String?, body: JsonObject): MutationResult = data.mutate(access,
        MutationRequest("POST", "/plans/$id/reopen", key.orEmpty(), body)) {
        allowed(body, setOf("expected_version"))
        MutationResult(200, shoppingJson.encodeToJsonElement(reopen(parseId(id), version(body))))
    }
    private fun version(body: JsonObject): Int {
        val expected = body["expected_version"] as? JsonPrimitive ?: invalid("expected_version", "INVALID_INTEGER")
        val version = if (!expected.isString) expected.intOrNull else null
        if (version == null || version <= 0) invalid("expected_version", "INVALID_INTEGER")
        return version
    }
    private fun allowed(body: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        if ((body.keys - required - optional).isNotEmpty()) throw ApiFailure(400, "UNKNOWN_FIELD")
        if (!body.keys.containsAll(required)) throw ApiFailure(400, "MALFORMED_REQUEST")
    }
    private fun string(value: JsonElement?, field: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(field, "INVALID_STRING")
    private fun selections(value: JsonElement?): List<Selection> {
        val array = value as? JsonArray ?: invalid("selections", "INVALID_ARRAY")
        if (array.size > 3) invalid("selections", "STORE_LIMIT_EXCEEDED")
        return array.map { element ->
            val obj = element as? JsonObject ?: invalid("selections", "INVALID_SELECTION")
            if ((obj.keys - setOf("retailer_id", "store_id")).isNotEmpty()) throw ApiFailure(400, "UNKNOWN_FIELD")
            Selection(obj["retailer_id"]?.takeUnless { it == JsonNull }?.let { string(it, "selections") },
                obj["store_id"]?.takeUnless { it == JsonNull }?.let { string(it, "selections") })
        }
    }
    private fun ids(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: invalid("category_ids", "INVALID_ARRAY")
        if (array.size > 1000) invalid("category_ids", "TOO_MANY")
        return array.map { string(it, "category_ids") }
    }
}

internal fun pageLimit(value: Int) { if (value !in 1..100) invalid("limit", "OUT_OF_RANGE") }
internal fun searchQuery(value: String?) { if ((value?.length ?: 0) > 200) invalid("q", "TOO_LONG") }
