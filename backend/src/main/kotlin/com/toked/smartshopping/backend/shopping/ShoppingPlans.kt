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
        val expected = body["expected_version"] as? JsonPrimitive ?: invalid("expected_version", "INVALID_INTEGER")
        val version = if (!expected.isString) expected.intOrNull else null
        if (version == null || version <= 0) invalid("expected_version", "INVALID_INTEGER")
        val result = updatePlan(parseId(id), version, if ("budget" in body) string(body["budget"], "budget") else null,
            if ("selections" in body) selections(body["selections"]) else null,
            if ("category_ids" in body) ids(body["category_ids"]) else null)
        MutationResult(200, shoppingJson.encodeToJsonElement(result))
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
