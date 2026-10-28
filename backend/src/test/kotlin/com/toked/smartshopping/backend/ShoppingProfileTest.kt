package com.toked.smartshopping.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.UUID
import kotlin.test.*

class ShoppingProfileTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var profiles: ShoppingProfiles
    private lateinit var account: AuthResult
    @Before fun setup() {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url == "jdbc:postgresql://127.0.0.1:55432/auth00_test")
        db = Database(url, "auth00_test", ""); db.migrate(); auth = AuthService(db); profiles = auth.shoppingProfiles()
        account = auth.register("${UUID.randomUUID()}@example.com", "Password sample!", "Password sample!", null)
        db.transaction { c ->
            execute(c, "INSERT INTO shopping_categories(id,name,status) VALUES ('test-food','Thực phẩm','ACTIVE'),('test-old','Cũ','INACTIVE') ON CONFLICT DO NOTHING")
            execute(c, "INSERT INTO shopping_brands(id,name,status) VALUES ('test-brand','Thương hiệu thử','ACTIVE') ON CONFLICT DO NOTHING")
        }
    }
    @After fun close() { if (::db.isInitialized) db.close() }
    private fun patch(text: String, key: String = UUID.randomUUID().toString(), token: String = account.tokens.access_token) =
        profiles.patch(token, key, Json.parseToJsonElement(text).jsonObject)
    private fun failure(code: String, block: () -> Unit): ApiFailure = assertFailsWith<ApiFailure>(block = block).also { assertEquals(code, it.code) }

    @Test fun defaultsPartialUpdateNullAndReadBackFromAuth() {
        assertEquals(ProfileDto(), profiles.get(account.tokens.access_token))
        val p = patch("""{"expected_version":1,"household_size":4,"default_period":"P1W","preferred_category_ids":["test-food"],"preferred_brand_ids":["test-brand"],"max_stores":2,"optimization_mode":"CHEAPEST"}""")
        assertEquals(2, p.version); assertEquals(4, p.household_size)
        assertEquals(p, auth.me(account.tokens.access_token).profile)
        val next = patch("""{"expected_version":2,"household_size":null,"default_period":null}""")
        assertNull(next.household_size); assertNull(next.default_period); assertEquals(p.preferred_category_ids, next.preferred_category_ids)
        assertEquals(2, next.max_stores)
        assertEquals(next, patch("""{"expected_version":3}"""))
    }
    @Test fun validationNeverMutatesAndReportsFields() {
        for ((field, value) in listOf("max_stores" to "4", "max_stores" to "0", "household_size" to "0", "household_size" to "1.5",
            "household_size" to "\"2\"", "default_period" to "\"P0D\"", "default_period" to "\"P-1M\"",
            "default_period" to "\"nonsense\"", "optimization_mode" to "\"INVALID\"", "max_stores" to "null",
            "preferred_category_ids" to "[\"missing\"]", "preferred_category_ids" to "[\"test-old\"]",
            "preferred_brand_ids" to "[\"test-brand\",\"test-brand\"]", "avoided_category_ids" to "null")) {
            val e = failure("VALIDATION_ERROR") { patch("""{"expected_version":1,"$field":$value}""") }
            assertEquals(field, e.fields!!.first().field)
        }
        failure("VALIDATION_ERROR") { patch("""{"expected_version":1,"preferred_category_ids":["test-food"],"avoided_category_ids":["test-food"]}""") }
        failure("VALIDATION_ERROR") { patch("""{"expected_version":1,"preferred_brand_ids":["test-brand"],"avoided_brand_ids":["test-brand"]}""") }
        assertEquals(ProfileDto(), profiles.get(account.tokens.access_token))
    }
    @Test fun idempotencyPrecedesVersionAndIsOwnerScoped() {
        val key = UUID.randomUUID().toString()
        val body = """{"expected_version":1,"max_stores":1}"""
        val saved = patch(body, key)
        db.transaction { c -> query(c, "SELECT payload FROM profile_requests WHERE owner_id=? AND request_key=?", UUID.fromString(account.user.id), key).use { s ->
            s.executeQuery().use { r -> assertTrue(r.next()); assertTrue(r.getString(1).matches(Regex("[0-9a-f]{64}"))) }
        } }
        patch("""{"expected_version":2,"max_stores":2}""")
        assertEquals(saved, patch("""{"max_stores":1,"expected_version":1}""", key))
        failure("IDEMPOTENCY_KEY_REUSED") { patch("""{"expected_version":1,"max_stores":3}""", key) }
        val conflict = failure("VERSION_CONFLICT") { patch(body) }
        assertEquals(3, conflict.details["current_version"]!!.jsonPrimitive.int)
        val other = auth.register("${UUID.randomUUID()}@example.com", "Password sample!", "Password sample!", null)
        assertEquals(saved, patch(body, key, other.tokens.access_token))
        assertEquals(2, profiles.get(account.tokens.access_token).max_stores)
        failure("UNKNOWN_FIELD") { patch("""{"expected_version":3,"user_id":"${other.user.id}"}""") }
    }
    @Test fun concurrentWritersAndReplayCommitOnlyOnce() = runBlocking {
        val body = """{"expected_version":1,"max_stores":1}"""
        val results = (1..2).map { async(Dispatchers.IO) { try { patch(body); "saved" } catch (e: ApiFailure) { e.code } } }.awaitAll()
        assertEquals(setOf("saved", "VERSION_CONFLICT"), results.toSet())
        val key = UUID.randomUUID().toString()
        val replayBody = """{"expected_version":2,"household_size":2}"""
        val replay = (1..2).map { async(Dispatchers.IO) { patch(replayBody, key) } }.awaitAll()
        assertEquals(replay[0], replay[1]); assertEquals(3, profiles.get(account.tokens.access_token).version)
    }
    @Test fun atomicRollbackAndExpiredReplayEntry() {
        db.transaction { execute(it, "ALTER TABLE profiles ADD CONSTRAINT profile_test_failure CHECK (household_size IS NULL) NOT VALID") }
        val key = UUID.randomUUID().toString()
        try { assertFailsWith<java.sql.SQLException> { patch("""{"expected_version":1,"household_size":2}""", key) } }
        finally { db.transaction { execute(it, "ALTER TABLE profiles DROP CONSTRAINT profile_test_failure") } }
        assertEquals(2, patch("""{"expected_version":1,"household_size":2}""", key).household_size)
        db.transaction { execute(it, "UPDATE profile_requests SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours' WHERE owner_id=?", UUID.fromString(account.user.id)) }
        assertEquals(3, patch("""{"expected_version":2,"household_size":3}""", key).household_size)
    }
    @Test fun referencePaginationFiltersAndOwnerBoundCursor() {
        val token = account.tokens.access_token
        val page = profiles.references(token,"categories", "",null,1,null)
        assertEquals(1,page.items.size); assertNotNull(page.next_cursor)
        assertEquals(1,profiles.references(token,"categories","",null,1,page.next_cursor).items.size)
        failure("INVALID_CURSOR") { profiles.references(token,"brands","",null,1,page.next_cursor) }
        val filtered = profiles.references(token,"categories","Thực",null,20,null).items
        assertTrue(filtered.any { it.id == "test-food" }); assertTrue(filtered.all { it.name.contains("Thực") })
        assertTrue(profiles.references(token,"brands","unknown",null,20,null).items.isEmpty())
    }
    @Test fun httpContractsAuthKeyUnknownAndTypedFields() = testApplication {
        application { authModule(auth) }
        val path = "/api/v1/me/shopping-profile"
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/categories").status)
        suspend fun send(body: String, key: String? = null) = client.patch(path) {
            bearerAuth(account.tokens.access_token); contentType(ContentType.Application.Json)
            if (key != null) header("Idempotency-Key",key); setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, send("""{"expected_version":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, send("""{"expected_version":1,"owner_id":"x"}""", "a").status)
        assertEquals(HttpStatusCode.UnprocessableEntity,send("""{"expected_version":1,"max_stores":4}""", "b").status)
        val success = send("""{"expected_version":1,"max_stores":1}""", "c")
        assertEquals(HttpStatusCode.OK, success.status)
        val json = Json.parseToJsonElement(success.bodyAsText()).jsonObject
        assertEquals(1,json["data"]!!.jsonObject["max_stores"]!!.jsonPrimitive.int)
        assertNotNull(json["meta"]!!.jsonObject["request_id"])
        auth.logout(account.tokens.refresh_token)
        assertEquals(HttpStatusCode.Unauthorized, client.get(path) { bearerAuth(account.tokens.access_token) }.status)
    }
}
