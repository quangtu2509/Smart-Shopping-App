package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

internal fun catalogOffer(id: String="offer-1",price: String?="30000"): Offer {
    val raw=Thread.currentThread().contextClassLoader!!.getResource("shopping/plan-view.json")!!.readText()
    val offer=JSONObject(raw).getJSONArray("items").getJSONObject(0).getJSONObject("selected_price_snapshot")
    offer.getJSONObject("ref").put("id",id)
    offer.put("price",price ?: JSONObject.NULL)
    return ShoppingContracts.offer(offer)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogControllerTest {
    private class Gateway : PlanGateway {
        var view=samplePlan().let {it.copy(plan=it.plan.copy(status=PlanState.BROWSING_PRODUCTS,catalogStatus="READY"))}
        var page=CatalogPage(listOf(catalogOffer()),"next","READY",emptyList(),emptyList())
        var failure: Exception?=null
        var refreshFailure: Exception?=null
        var gate: CompletableDeferred<Unit>?=null
        val refreshes=mutableListOf<CatalogRefreshIntent>()
        val queries=mutableListOf<Pair<CatalogFilter,String?>>()
        override suspend fun catalog(planId: String,filter: CatalogFilter,cursor: String?): CatalogPage {queries+=filter to cursor;gate?.await();failure?.let {throw it};return page}
        override suspend fun refreshCatalog(planId: String,intent: CatalogRefreshIntent): ShoppingPlan {
            refreshes+=intent;refreshFailure?.let {throw it};view=view.copy(plan=view.plan.copy(version=view.plan.version+1));return view.plan
        }
        override suspend fun productDetail(planId: String,productId: String)=CatalogDetail(catalogOffer().product!!,listOf(catalogOffer()),emptyList())
        override suspend fun planMaxStores()=3
        override suspend fun planReferences(kind: String,cursor: String?)=PlanReferencePage(emptyList(),null)
        override suspend fun planStores(retailerId: String?,query: String,cursor: String?)=PlanStorePage(emptyList(),null,emptyList(),emptyList())
        override suspend fun planStore(id: String)=PlanStore(id,"r1","Store",null,false)
        override suspend fun plans(cursor: String?)=PlanPage(listOf(view.plan),null)
        override suspend fun plan(id: String)=view
        override suspend fun savePlan(mutation: PlanMutation)=view
    }
    private fun TestScope.controller(g: Gateway,ended: ()->Unit={})=CatalogController(g,backgroundScope+UnconfinedTestDispatcher(testScheduler),{},ended)

    @Test fun searchFiltersAndPaginationKeepPlanContext()=runTest {
        val g=Gateway();val c=controller(g);c.open(g.view)
        c.onAction(CatalogAction.Query(" sữa & gạo "));c.onAction(CatalogAction.Search)
        assertEquals("sữa & gạo",g.queries.last().first.query)
        c.onAction(CatalogAction.Category("c1"));assertEquals("c1",g.queries.last().first.category)
        g.page=g.page.copy(items=listOf(catalogOffer("offer-2")),nextCursor=null)
        c.onAction(CatalogAction.More)
        assertEquals("next",g.queries.last().second);assertEquals(2,c.state.value.items.size)
        assertNull(c.state.value.nextCursor)
    }
    @Test fun nextPageFailureRetainsItemsAndCursor()=runTest {
        val g=Gateway();val c=controller(g);c.open(g.view);g.failure=AuthFailure.Network()
        c.onAction(CatalogAction.More)
        assertEquals(1,c.state.value.items.size);assertEquals("next",c.state.value.nextCursor);assertNotNull(c.state.value.error)
    }
    @Test fun refreshNetworkRetryReusesExactIntentAndPolls()=runTest {
        val g=Gateway();val c=controller(g);c.open(g.view);g.refreshFailure=AuthFailure.Network()
        c.onAction(CatalogAction.Refresh);val pending=c.state.value.pending!!
        assertEquals(1,JSONObject(pending.body).getInt("expected_version"))
        g.refreshFailure=null;g.page=g.page.copy(nextCursor=null)
        c.onAction(CatalogAction.Refresh);advanceTimeBy(1600)
        assertEquals(listOf(pending,pending),g.refreshes)
        assertNull(c.state.value.pending);assertFalse(c.state.value.refreshing)
    }
    @Test fun closingOrSwitchingPlanDiscardsCancelledResponses()=runTest {
        val g=Gateway();g.gate=CompletableDeferred();val c=controller(g);c.open(g.view)
        c.clear();g.gate!!.complete(Unit);runCurrent()
        assertNull(c.state.value.plan);assertTrue(c.state.value.items.isEmpty())
    }
    @Test fun sessionEndClearsOwnerData()=runTest {
        val g=Gateway();var ended=false;val c=controller(g){ended=true};g.failure=AuthFailure.SessionEnded();c.open(g.view)
        assertTrue(ended);assertNull(c.state.value.plan);assertTrue(c.state.value.items.isEmpty())
    }
    @Test fun cursorChangeRequiresReloadWithoutDroppingLoadedItems()=runTest {
        val g=Gateway();val c=controller(g);c.open(g.view)
        g.failure=AuthFailure.Http(400,"INVALID_CURSOR",emptyMap(),null);c.onAction(CatalogAction.More)
        assertNull(c.state.value.nextCursor);assertEquals(1,c.state.value.items.size);assertNotNull(c.state.value.error)
    }
    @Test fun partialAndMissingPriceRemainVisibleAndCooldownBlocksRetry()=runTest {
        val g=Gateway();g.page=g.page.copy(items=listOf(catalogOffer(price=null)),status="PARTIAL",retailers=listOf(RetailerCatalogResult("r1","PARTIAL","PROVIDER_RATE_LIMITED",3)))
        val c=controller(g);c.open(g.view);assertNull(c.state.value.items.single().price)
        c.onAction(CatalogAction.Refresh);assertTrue(g.refreshes.isEmpty())
        advanceTimeBy(3100);assertEquals(0,c.state.value.retrySeconds)
        c.onAction(CatalogAction.Details(g.page.items.single().product!!.id));assertNotNull(c.state.value.detail)
        c.onAction(CatalogAction.CloseDetails);assertNull(c.state.value.selectedProduct)
    }
}

class CatalogApiTest {
    private lateinit var server: MockWebServer
    @Before fun start(){server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)}
    @After fun stop(){server.shutdown()}
    private fun api()=AuthApi(server.url("/api/v1/").newBuilder().host("127.0.0.1").build().toString(),allowLocalHttp=true)
    private fun fixture(): JSONObject=JSONObject(javaClass.classLoader!!.getResource("shopping/plan-view.json")!!.readText())
    private fun data(value: JSONObject)=JSONObject().put("data",value).toString()
    private fun page():JSONObject=JSONObject().put("items",JSONArray().put(fixture().getJSONArray("items").getJSONObject(0).getJSONObject("selected_price_snapshot")))
        .put("next_cursor",JSONObject.NULL).put("status","READY").put("retailer_results",JSONArray()).put("warnings",JSONArray())
    @Test fun encodedCatalogAndDetailUseProtectedSmartShoppingApi()=runTest {
        val api=api()
        server.enqueue(MockResponse().setBody(data(page())))
        assertEquals(1,api.catalog("access","plan/id",CatalogFilter(query="sữa & gạo",sort="PRICE_DESC"),"cursor+/").items.size)
        val request=server.takeRequest();assertEquals("Bearer access",request.getHeader("Authorization"))
        assertEquals("/api/v1/plans/plan%2Fid/catalog",request.requestUrl!!.encodedPath)
        assertEquals("sữa & gạo",request.requestUrl!!.queryParameter("q"));assertEquals("cursor+/",request.requestUrl!!.queryParameter("cursor"))
        val offer=fixture().getJSONArray("items").getJSONObject(0).getJSONObject("selected_price_snapshot")
        server.enqueue(MockResponse().setBody(data(JSONObject().put("product",offer.getJSONObject("product")).put("offers",JSONArray().put(offer)).put("warnings",JSONArray()))))
        api.productDetail("access","plan/id","product/id")
        val detail=server.takeRequest();assertEquals("/api/v1/products/product%2Fid",detail.requestUrl!!.encodedPath);assertEquals("plan/id",detail.requestUrl!!.queryParameter("plan_id"))
    }
    @Test fun refreshKeepsBodyAndIdempotencyKey()=runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody(data(JSONObject().put("plan",fixture().getJSONObject("plan")).put("catalog_status","LOADING"))))
        val intent=CatalogRefreshIntent("same-intent","{\"expected_version\":7}")
        api().refreshCatalog("access","p",intent)
        val request=server.takeRequest();assertEquals("POST",request.method);assertEquals(intent.body,request.body.readUtf8());assertEquals(intent.key,request.getHeader("Idempotency-Key"))
    }
    @Test fun numericPricesAndUnknownStatusesAreRejected() {
        val invalid=page();invalid.getJSONArray("items").getJSONObject(0).put("price",30)
        try {CatalogDecoders.page(data(invalid));fail()}catch(_:AuthFailure.InvalidResponse){}
        try {CatalogDecoders.page(data(page().put("status","SUCCESS")));fail()}catch(_:AuthFailure.InvalidResponse){}
    }
    @Test fun accessRotationKeepsCatalogRefreshIntentIdentical()=runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"ACCESS_TOKEN_EXPIRED","field_errors":[],"details":{}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"new-access","refresh_token":"new-refresh","auth_session_id":"session","token_type":"Bearer","expires_in":900,"refresh_expires_at":"2026-11-12T00:00:00Z"}}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody(data(JSONObject().put("plan",fixture().getJSONObject("plan")).put("catalog_status","LOADING"))))
        val repository=AuthSessionRepository(api(),MemorySessionStore());repository.save(sampleSession())
        repository.refreshCatalog("p",CatalogRefreshIntent("stable-key","{\"expected_version\":1}"))
        val first=server.takeRequest();val refresh=server.takeRequest();val repeated=server.takeRequest()
        assertEquals("/api/v1/auth/refresh",refresh.path)
        assertEquals(first.body.readUtf8(),repeated.body.readUtf8());assertEquals(first.getHeader("Idempotency-Key"),repeated.getHeader("Idempotency-Key"))
        assertEquals("Bearer new-access",repeated.getHeader("Authorization"))
    }
}
