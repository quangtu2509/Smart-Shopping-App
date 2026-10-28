package com.toked.smartshopping.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.Base64
import java.util.UUID

@Serializable data class ShoppingReference(val id: String, val name: String, val status: String, val parent_id: String? = null)

class ShoppingProfiles(private val db: Database, private val auth: AuthService) {
    private val json = Json { encodeDefaults = true }
    private val security = Security()

    fun get(access: String): ProfileDto = db.transaction { c -> read(c, auth.authenticatedOwner(c, access)) }

    fun patch(access: String, key: String?, body: JsonObject): ProfileDto = db.transaction { c ->
        val owner = auth.authenticatedOwner(c, access)
        if (key.isNullOrBlank()) throw ApiFailure(400, "IDEMPOTENCY_KEY_REQUIRED")
        if (key.length > 200) invalid("Idempotency-Key", "TOO_LONG")
        // Authentication locks the owner first; all writers use the same lock ordering as auth.
        val old = read(c, owner)
        val payload = security.digest(canonical(body).toString())
        execute(c, "DELETE FROM profile_requests WHERE owner_id=? AND created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours'", owner)
        query(c, "SELECT payload,response FROM profile_requests WHERE owner_id=? AND request_key=?", owner, key).use { s ->
            s.executeQuery().use { r -> if (r.next()) {
                if (r.getString(1) != payload) throw ApiFailure(409, "IDEMPOTENCY_KEY_REUSED")
                return@transaction json.decodeFromString<ProfileDto>(r.getString(2))
            } }
        }
        val allowed = setOf("expected_version", "household_size", "default_period", "preferred_category_ids",
            "avoided_category_ids", "preferred_brand_ids", "avoided_brand_ids", "max_stores", "optimization_mode")
        if ((body.keys - allowed).isNotEmpty()) throw ApiFailure(400, "UNKNOWN_FIELD")
        if ("expected_version" !in body) throw ApiFailure(400, "MALFORMED_REQUEST")
        val expected = integer(body["expected_version"], "expected_version")
        if (expected <= 0) invalid("expected_version", "MUST_BE_POSITIVE")
        if (expected != old.version) throw ApiFailure(409, "VERSION_CONFLICT", details = buildJsonObject { put("current_version", old.version) })
        val household = if ("household_size" !in body) old.household_size else if (body["household_size"] == JsonNull) null
            else integer(body["household_size"], "household_size").also { if (it <= 0) invalid("household_size", "MUST_BE_POSITIVE") }
        val period = if ("default_period" !in body) old.default_period else if (body["default_period"] == JsonNull) null
            else string(body["default_period"], "default_period").also {
                if (!positivePeriod(it)) invalid("default_period", "INVALID_PERIOD")
            }
        val max = if ("max_stores" in body) integer(body["max_stores"], "max_stores") else old.max_stores
        if (max !in 1..3) invalid("max_stores", "OUT_OF_RANGE")
        val mode = if ("optimization_mode" in body) string(body["optimization_mode"], "optimization_mode") else old.optimization_mode
        if (mode !in setOf("CHEAPEST", "CONVENIENT", "BALANCED")) invalid("optimization_mode", "INVALID_ENUM")
        fun ids(field: String, existing: List<String>, table: String): List<String> {
            if (field !in body) return existing
            val array = body[field] as? JsonArray ?: invalid(field, "INVALID_ARRAY")
            val ids = array.map { string(it, field) }
            if (ids.distinct().size != ids.size) invalid(field, "INVALID_SELECTION")
            ids.forEach { id ->
                val valid = query(c, "SELECT 1 FROM $table WHERE id=? AND status='ACTIVE'", id).use { s -> s.executeQuery().use { it.next() } }
                // Retain an old inactive preference until the user explicitly removes it.
                if (!valid && id !in existing) invalid(field, "UNKNOWN_REFERENCE")
            }
            return ids
        }
        val pc = ids("preferred_category_ids", old.preferred_category_ids, "shopping_categories")
        val ac = ids("avoided_category_ids", old.avoided_category_ids, "shopping_categories")
        val pb = ids("preferred_brand_ids", old.preferred_brand_ids, "shopping_brands")
        val ab = ids("avoided_brand_ids", old.avoided_brand_ids, "shopping_brands")
        if (pc.intersect(ac.toSet()).isNotEmpty()) invalid("avoided_category_ids", "PREFERENCE_OVERLAP")
        if (pb.intersect(ab.toSet()).isNotEmpty()) invalid("avoided_brand_ids", "PREFERENCE_OVERLAP")
        val candidate = ProfileDto(old.version, household, period, pc, ac, pb, ab, max, mode)
        val next = if (candidate == old) old else candidate.copy(version = old.version + 1)
        execute(c, """UPDATE profiles SET version=?,household_size=?,default_period=?,preferred_category_ids=?,
            avoided_category_ids=?,preferred_brand_ids=?,avoided_brand_ids=?,max_stores=?,optimization_mode=? WHERE user_id=?""",
            next.version, household, period, c.createArrayOf("text", pc.toTypedArray()), c.createArrayOf("text", ac.toTypedArray()),
            c.createArrayOf("text", pb.toTypedArray()), c.createArrayOf("text", ab.toTypedArray()), max, mode, owner)
        execute(c, "INSERT INTO profile_requests(owner_id,request_key,payload,response) VALUES (?,?,?,?)", owner, key, payload, json.encodeToString(next))
        next
    }

    fun references(access: String, kind: String, q: String?, parent: String?, limit: Int, cursor: String?): Page<ShoppingReference> = db.transaction { c ->
        val owner = auth.authenticatedOwner(c, access)
        require(kind in setOf("categories", "brands"))
        if (limit !in 1..100) invalid("limit", "OUT_OF_RANGE")
        if ((q?.length ?: 0) > 200) invalid("q", "TOO_LONG")
        val scope = listOf(owner.toString(), kind, q, parent)
        val after = cursor?.let {
            val decoded = try { json.decodeFromString<List<String?>>(String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)) }
                catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
            if (decoded.size != 5 || decoded.take(4) != scope) throw ApiFailure(400, "INVALID_CURSOR")
            decoded.last() ?: throw ApiFailure(400, "INVALID_CURSOR")
        }
        val table = if (kind == "categories") "shopping_categories" else "shopping_brands"
        val clauses = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        if (q != null) { clauses += "position(lower(?) in lower(name)) > 0"; values += q }
        if (parent != null) {
            if (kind == "categories") { clauses += "parent_id=?"; values += parent }
            else clauses += "FALSE"
        }
        if (after != null) { clauses += "id > ?"; values += after }
        values += limit + 1
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val rows = query(c, "SELECT id,name,status,${if (kind == "categories") "parent_id" else "NULL AS parent_id"} FROM $table$where ORDER BY id LIMIT ?", *values.toTypedArray()).use { s ->
            s.executeQuery().use { r -> buildList { while (r.next()) add(ShoppingReference(r.getString(1), r.getString(2), r.getString(3), r.getString(4))) } }
        }
        val items=rows.take(limit)
        Page(items, if (rows.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(scope + items.last().id).toByteArray()) else null)
    }

    private fun read(c: Connection, owner: UUID): ProfileDto {
        execute(c, "INSERT INTO profiles(user_id) VALUES (?) ON CONFLICT DO NOTHING", owner)
        return query(c, "SELECT * FROM profiles WHERE user_id=? FOR UPDATE", owner).use { s -> s.executeQuery().use { r ->
            check(r.next())
            fun ids(name: String) = (r.getArray(name).array as Array<*>).map { it.toString() }
            ProfileDto(r.getInt("version"), r.getObject("household_size") as Int?, r.getString("default_period"),
                ids("preferred_category_ids"), ids("avoided_category_ids"), ids("preferred_brand_ids"), ids("avoided_brand_ids"), r.getInt("max_stores"), r.getString("optimization_mode"))
        } }
    }
    private fun integer(value: JsonElement?, field: String): Int {
        val p = value as? JsonPrimitive ?: invalid(field, "INVALID_INTEGER")
        return if (!p.isString) p.intOrNull ?: invalid(field, "INVALID_INTEGER") else invalid(field, "INVALID_INTEGER")
    }
    private fun string(value: JsonElement?, field: String): String {
        val p = value as? JsonPrimitive ?: invalid(field, "INVALID_STRING")
        return if (p.isString) p.content else invalid(field, "INVALID_STRING")
    }
    private fun invalid(field: String, code: String): Nothing = throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError(field, code)))
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}

internal fun positivePeriod(value: String): Boolean = try {
    val period = java.time.Period.parse(value)
    value.startsWith("P") && !period.isZero && !period.isNegative
} catch (_: java.time.format.DateTimeParseException) { false } catch (_: ArithmeticException) { false }
