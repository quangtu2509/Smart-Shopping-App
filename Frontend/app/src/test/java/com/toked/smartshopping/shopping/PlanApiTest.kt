package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class PlanApiTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    @After fun stop() { server.shutdown() }
    private fun api() = AuthApi(server.url("/api/v1/").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)
    private fun fixture() = javaClass.classLoader!!.getResource("shopping/plan-view.json")!!.readText()
    private fun envelope(data: JSONObject) = JSONObject().put("data", data).put("meta", JSONObject().put("request_id", "fixture")).toString()
    private fun viewResponse(status: Int = 200) = MockResponse().setResponseCode(status).setBody(envelope(JSONObject(fixture())))

    @Test fun referenceAndStoreQueriesEncodeFiltersAndCursor() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"items":[{"id":"r1","name":"Fixture chain","status":"ACTIVE"}],"next_cursor":null}}"""))
        val gateway = api()
        assertEquals("r1", gateway.planReferences("access", "retailers", "a+b/c").items.single().id)
        val ref = server.takeRequest()
        assertEquals("a+b/c", ref.requestUrl!!.queryParameter("cursor")); assertEquals("20", ref.requestUrl!!.queryParameter("limit"))
        assertEquals("Bearer access", ref.getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("""{"data":{"map":{"stores":[],"status":"EMPTY","attribution":[],"warnings":[]},"next_cursor":null}}"""))
        assertTrue(gateway.planStores("access", "r/1", "Khu A & B", null).items.isEmpty())
        val stores = server.takeRequest().requestUrl!!
        assertEquals("-180,-90,180,90", stores.queryParameter("bbox")); assertEquals("r/1", stores.queryParameter("retailer_ids"))
        assertEquals("Khu A & B", stores.queryParameter("q"))
    }
    @Test fun createAndPatchUseStableBusinessBodyAndMutationKey() = runTest {
        val body = PlanDraft("500000", selections = listOf(Selection("r1", null)), categoryIds = listOf("c1")).body()
        server.enqueue(viewResponse(201)); server.enqueue(viewResponse())
        val gateway = api()
        gateway.savePlan("access", PlanMutation("same-key", body))
        val created = server.takeRequest()
        assertEquals("POST", created.method); assertEquals("/api/v1/plans", created.path)
        assertEquals(body, created.body.readUtf8()); assertEquals("same-key", created.getHeader("Idempotency-Key"))
        gateway.savePlan("access", PlanMutation("edit-key", body, "opaque/id"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method); assertEquals("/api/v1/plans/opaque%2Fid", patch.path)
        assertEquals("edit-key", patch.getHeader("Idempotency-Key"))
    }
    @Test fun listReadsPlanTotalsWithoutInventingOrRequiringItemSnapshots() = runTest {
        val p = JSONObject(fixture()).getJSONObject("plan")
        server.enqueue(MockResponse().setBody(envelope(JSONObject().put("items", JSONArray().put(p)).put("next_cursor", "next"))))
        val page = api().plans("access", null)
        assertEquals("30", page.items.single().budgetSummary.selectedTotal.amount.toPlainString())
        assertFalse(page.items.single().budgetSummary.complete); assertEquals("next", page.nextCursor)
        assertEquals("20", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }
    @Test fun numericMoneyAndMalformedStoreResponsesAreRejected() = runTest {
        val invalid = JSONObject(fixture()).apply { getJSONObject("plan").put("budget", 100) }
        server.enqueue(MockResponse().setBody(envelope(invalid)))
        try { api().plan("access", "id"); fail() } catch (_: AuthFailure.InvalidResponse) { }
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"data":{"map":{"status":"SUCCESS","stores":[],"warnings":[],"attribution":[]},"next_cursor":null}}"""))
        try { api().planStores("access", null, "", null); fail() } catch (_: AuthFailure.InvalidResponse) { }
    }
    @Test fun expiredAccessRefreshesOnceAndRepeatsIdenticalPlanIntention() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"ACCESS_TOKEN_EXPIRED","field_errors":[],"details":{}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"new-access","refresh_token":"new-refresh","auth_session_id":"session","token_type":"Bearer","expires_in":900,"refresh_expires_at":"2026-11-12T00:00:00Z"}}"""))
        server.enqueue(viewResponse(201))
        val store = MemorySessionStore(); val repository = AuthSessionRepository(api(), store)
        repository.save(sampleSession())
        val body = PlanDraft("500000", selections = listOf(Selection("r1", null)), categoryIds = listOf("c1")).body()
        repository.savePlan(PlanMutation("stable-intention", body))
        val first = server.takeRequest(); val refresh = server.takeRequest(); val repeated = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertEquals(first.body.readUtf8(), repeated.body.readUtf8())
        assertEquals(first.getHeader("Idempotency-Key"), repeated.getHeader("Idempotency-Key"))
        assertEquals("Bearer old-access", first.getHeader("Authorization")); assertEquals("Bearer new-access", repeated.getHeader("Authorization"))
        assertEquals("new-refresh", store.saved!!.refreshToken)
    }
}
