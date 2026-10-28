package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.shopping.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.*

class ShoppingPlansTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var plans: ShoppingPlans
    private lateinit var account: AuthResult
    private lateinit var stranger: AuthResult
    private val draft = """{"budget":"500000","currency":"VND","selections":[{"retailer_id":"plan-retailer-a","store_id":null}],"category_ids":["plan-food"]}"""
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    @Before fun setup() {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url == "jdbc:postgresql://127.0.0.1:55432/auth00_test")
        db = Database(url, "auth00_test", ""); db.migrate(); resetAuthTestData(db)
        auth = AuthService(db); plans = auth.shoppingPlans()
        account = auth.register("plan-owner@example.com", "Plan test password!", "Plan test password!", null)
        stranger = auth.register("plan-stranger@example.com", "Plan test password!", "Plan test password!", null)
        seedPlanReferences(db, account.user.id)
    }
    @After fun close() { if (::db.isInitialized) db.close() }
    private fun create(body: String = draft, key: String = UUID.randomUUID().toString(), token: String = account.tokens.access_token) = plans.create(token, key, obj(body))
    private fun view(result: MutationResult) = Json.decodeFromJsonElement<PlanView>(result.data)
    private fun patch(id: String, body: String, key: String = UUID.randomUUID().toString()) = plans.patch(account.tokens.access_token, id, key, obj(body))
    private fun failure(code: String, block: () -> Any?): ApiFailure = assertFailsWith<ApiFailure> { block() }.also { assertEquals(code, it.code) }

    @Test fun draftPersistenceReplayOwnerAndProfileDefaults() {
        val access = account.tokens.access_token
        auth.shoppingProfiles().patch(access, "profile-limit", obj("""{"expected_version":1,"max_stores":2,"optimization_mode":"CHEAPEST"}"""))
        val result = create(key = "same-intention"); val created = view(result)
        assertEquals(201, result.status); assertEquals("/api/v1/plans/${created.plan.id}", result.location)
        assertEquals(1, created.plan.version); assertEquals(0, created.plan.revision); assertEquals(PlanStatus.DRAFT, created.plan.status)
        assertEquals(2, created.plan.max_stores); assertEquals("0", created.plan.budget_summary.selected_total)
        assertEquals(result, create(key = "same-intention"))
        assertEquals(created, plans.get(access, created.plan.id))
        failure("IDEMPOTENCY_KEY_REUSED") { create(draft.replace("500000", "600000"), "same-intention") }
        failure("RESOURCE_NOT_FOUND") { plans.get(stranger.tokens.access_token, created.plan.id) }
        failure("RESOURCE_NOT_FOUND") { plans.patch(stranger.tokens.access_token, created.plan.id, "same-intention", obj("""{"expected_version":1}""")) }
        auth.shoppingProfiles().patch(access, "change-profile", obj("""{"expected_version":2,"max_stores":1}"""))
        assertEquals(2, plans.get(access, created.plan.id).plan.max_stores)
        assertEquals(1, plans.list(access, "DRAFT", 20, null).items.size)
        assertTrue(plans.list(stranger.tokens.access_token, null, 20, null).items.isEmpty())
        auth.logout(account.tokens.refresh_token)
        failure("SESSION_REVOKED") { create(key = "same-intention") }
    }

    @Test fun invalidInputsRollbackWithoutCreatingReplayOrPlan() {
        for ((field, bad) in listOf("budget" to "0", "budget" to "-1", "budget" to "1.5", "budget" to "1e3", "currency" to "USD")) {
            val body = JsonObject(obj(draft) + (field to JsonPrimitive(bad)))
            failure("VALIDATION_ERROR") { plans.create(account.tokens.access_token, "bad-$field-$bad", body) }
        }
        for ((field, bad) in listOf("budget" to JsonPrimitive(10), "selections" to JsonArray(emptyList()),
            "category_ids" to JsonArray(emptyList()), "category_ids" to JsonArray(listOf(JsonPrimitive("plan-old"))),
            "category_ids" to JsonArray(listOf(JsonPrimitive("plan-food"), JsonPrimitive("plan-food"))))) {
            failure("VALIDATION_ERROR") { plans.create(account.tokens.access_token, "bad-array", JsonObject(obj(draft) + (field to bad))) }
        }
        failure("UNKNOWN_FIELD") { create(draft.dropLast(1) + ",\"owner_id\":\"${stranger.user.id}\"}") }
        failure("IDEMPOTENCY_KEY_REQUIRED") { plans.create(account.tokens.access_token, null, obj(draft)) }
        assertTrue(plans.list(account.tokens.access_token, null, 20, null).items.isEmpty())
        db.transaction { c -> query(c, "SELECT count(*) FROM shopping_mutations").use { s -> s.executeQuery().use { r -> r.next(); assertEquals(0, r.getInt(1)) } } }
    }

    @Test fun chainBranchPrivateSelectionValidationAndLimits() {
        fun selected(values: String) = JsonObject(obj(draft) + ("selections" to Json.parseToJsonElement(values)))
        fun attempt(values: String) = plans.create(account.tokens.access_token, UUID.randomUUID().toString(), selected(values))
        failure("VALIDATION_ERROR") { attempt("""[{"retailer_id":"plan-retailer-a"},{"retailer_id":"plan-retailer-a"}]""") }
        failure("VALIDATION_ERROR") { attempt("""[{"retailer_id":"plan-retailer-a"},{"store_id":"plan-branch-a"}]""") }
        failure("VALIDATION_ERROR") { attempt("""[{"retailer_id":"plan-retailer-b","store_id":"plan-branch-a"}]""") }
        failure("VALIDATION_ERROR") { attempt("""[{"retailer_id":"plan-retailer-a"},{"retailer_id":"plan-retailer-b"},{"retailer_id":"plan-retailer-c"},{"retailer_id":"plan-retailer-d"}]""") }
        val branch = view(attempt("""[{"store_id":"plan-branch-a"}]""")).plan.selections.single()
        assertEquals(Selection("plan-retailer-a", "plan-branch-a"), branch)
        val privateId = "plan-private-${account.user.id}"
        assertNull(view(attempt("""[{"store_id":"$privateId"}]""")).plan.selections.single().retailer_id)
        failure("RESOURCE_NOT_FOUND") { plans.create(stranger.tokens.access_token, "other-private", selected("""[{"store_id":"$privateId"}]""")) }
        auth.shoppingProfiles().patch(account.tokens.access_token, "limit", obj("""{"expected_version":1,"max_stores":1}"""))
        failure("VALIDATION_ERROR") { attempt("""[{"retailer_id":"plan-retailer-a"},{"retailer_id":"plan-retailer-b"}]""") }
    }

    @Test fun patchIsAtomicOneVersionNoOpAndPreservesItemsWhenContextChanges() {
        val original = view(create()); val id = original.plan.id
        assertEquals(1, view(patch(id, """{"expected_version":1,"budget":"500000.00"}""")).plan.version)
        val data = ShoppingData(db, auth)
        fun mutate(block: ShoppingWrite.() -> PlanView) = data.mutate(account.tokens.access_token,
            MutationRequest("POST", "/plans/$id/test", UUID.randomUUID().toString(), JsonObject(emptyMap()))) {
            MutationResult(200, Json.encodeToJsonElement(block()))
        }
        mutate { catalogState(UUID.fromString(id), 1, CatalogStatus.LOADING) }
        mutate { catalogState(UUID.fromString(id), 2, CatalogStatus.READY) }
        val item = view(mutate { addItem(UUID.fromString(id), 3, ItemInput("1", custom_name = "Fixture custom item", unit = "piece")) }).items.single()
        val body = """{"expected_version":4,"budget":"600000","selections":[{"retailer_id":"plan-retailer-b"}],"category_ids":["plan-home"]}"""
        val result = patch(id, body, "edit-once"); val changed = view(result)
        assertEquals(5, changed.plan.version); assertEquals(PlanStatus.BUILDING_LIST, changed.plan.status)
        assertEquals("FAILED", changed.plan.catalog_status.name); assertEquals(item, changed.items.single())
        assertTrue(changed.warnings.any { it.code == "PLAN_CONTEXT_CHANGED" && it.affected_ids == listOf(item.id) })
        assertEquals(result, patch(id, body, "edit-once"))
        failure("VERSION_CONFLICT") { patch(id, body) }
        failure("VALIDATION_ERROR") { patch(id, """{"expected_version":5,"budget":"900000","category_ids":["missing"]}""") }
        assertEquals("600000", plans.get(account.tokens.access_token, id).plan.budget)
        assertEquals(5, plans.get(account.tokens.access_token, id).plan.version)
        mutate { appendRevision(UUID.fromString(id), 5); plan(UUID.fromString(id)) }
        for (state in listOf("FINALIZED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "EXPIRED", "LOADING_PRODUCTS")) {
            db.transaction { execute(it, "UPDATE shopping_plans SET status=? WHERE id=?", state, UUID.fromString(id)) }
            failure("INVALID_STATE") { patch(id, """{"expected_version":6,"budget":"700000"}""") }
        }
    }

    @Test fun concurrentCreateAndWritersHaveExactlyOneCommitPerIntention() = runBlocking {
        val created = (1..2).map { async(Dispatchers.IO) { create(key = "concurrent-create") } }.awaitAll()
        assertEquals(created[0], created[1]); val id = view(created[0]).plan.id
        val edited = (1..2).map { i -> async(Dispatchers.IO) {
            try { patch(id, """{"expected_version":1,"budget":"${600000 + i}"}"""); "saved" }
            catch (failure: ApiFailure) { failure.code }
        } }.awaitAll()
        assertEquals(setOf("saved", "VERSION_CONFLICT"), edited.toSet())
        assertEquals(2, plans.get(account.tokens.access_token, id).plan.version)
    }

    @Test fun listAndStoreCursorsAreOwnerFilterScopedAndPrivateStoresStayPrivate() {
        val created = (1..3).map { view(create()).plan }
        val token = account.tokens.access_token
        val first = plans.list(token, "DRAFT", 1, null); assertNotNull(first.next_cursor)
        val second = plans.list(token, "DRAFT", 2, first.next_cursor)
        assertNull(second.next_cursor); assertEquals(3, (first.items + second.items).map { it.id }.distinct().size)
        assertEquals(created.reversed().map { it.id }, (first.items + second.items).map { it.id })
        failure("INVALID_CURSOR") { plans.list(stranger.tokens.access_token, "DRAFT", 1, first.next_cursor) }
        failure("INVALID_CURSOR") { plans.list(token, null, 1, first.next_cursor) }
        failure("VALIDATION_ERROR") { plans.list(token, "UNKNOWN", 20, null) }
        val stores = plans.stores(token, "105,9,107,11", null, null, 1, null)
        assertNotNull(stores.next_cursor)
        assertEquals("PARTIAL", stores.map.status)
        val all = stores.map.stores + plans.stores(token, "105,9,107,11", null, null, 20, stores.next_cursor).map.stores
        assertTrue(all.any { it.can_edit && it.visibility == "PRIVATE" })
        assertEquals(1, plans.stores(stranger.tokens.access_token, "105,9,107,11", null, null, 20, null).map.stores.size)
        failure("RESOURCE_NOT_FOUND") { plans.store(stranger.tokens.access_token, "plan-private-${account.user.id}") }
        failure("INVALID_CURSOR") { plans.stores(token, "105,9,107,11", "new filter", null, 20, stores.next_cursor) }
        failure("VALIDATION_ERROR") { plans.stores(token, "NaN,0,1,2", null, null, 20, null) }
        assertEquals("EMPTY", plans.stores(token, "0,0,1,1", null, null, 20, null).map.status)
        assertEquals(1, auth.shoppingProfiles().references(token, "retailers", "Cửa hàng thử nghiệm A", null, 20, null).items.size)
    }

    @Test fun httpContractAndReadbackUseRealPostgres() = testApplication {
        application { authModule(auth) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/plans").status)
        val response = client.post("/api/v1/plans") {
            bearerAuth(account.tokens.access_token); contentType(ContentType.Application.Json)
            header("Idempotency-Key", "http-create"); setBody(draft)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = obj(response.bodyAsText()); val id = body["data"]!!.jsonObject["plan"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("/api/v1/plans/$id", response.headers[HttpHeaders.Location]); assertNotNull(body["meta"])
        val read = client.get("/api/v1/plans/$id") { bearerAuth(account.tokens.access_token) }
        assertEquals(body["data"], obj(read.bodyAsText())["data"])
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/plans/$id") { bearerAuth(stranger.tokens.access_token) }.status)
        val patched = client.patch("/api/v1/plans/$id") {
            bearerAuth(account.tokens.access_token); contentType(ContentType.Application.Json)
            header("Idempotency-Key", "http-edit"); setBody("""{"expected_version":1,"budget":"750000"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val list = client.get("/api/v1/plans?status=DRAFT&limit=1") { bearerAuth(account.tokens.access_token) }
        assertEquals(1, obj(list.bodyAsText())["data"]!!.jsonObject["items"]!!.jsonArray.size)
        val refs = client.get("/api/v1/retailers?limit=1") { bearerAuth(account.tokens.access_token) }
        assertNotNull(obj(refs.bodyAsText())["data"]!!.jsonObject["next_cursor"])
        val stores = client.get("/api/v1/map/stores?bbox=105,9,107,11&retailer_ids=plan-retailer-a") { bearerAuth(account.tokens.access_token) }
        assertEquals(HttpStatusCode.OK, stores.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/stores/plan-branch-a") { bearerAuth(account.tokens.access_token) }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/v1/plans?limit=not-an-int") { bearerAuth(account.tokens.access_token) }.status)
    }
}
