package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.catalog.*
import com.toked.smartshopping.backend.shopping.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class CatalogServiceTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var account: AuthResult
    private lateinit var products: ProductService
    private val now = Instant.parse("2026-10-28T01:00:00Z")
    private val clock = Clock.fixed(now,ZoneOffset.UTC)
    private val provider = FakeCatalog()
    private var retailer = ""
    private var category = ""
    private var otherRetailer = ""
    private var otherCategory = ""
    private var planId = ""
    private val token get() = account.tokens.access_token
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun item(id: String, website: String = "https://one.example.test", price: String? = "10000") = ProviderProduct(
        id,id,"Mặt hàng $id",website,"catalog-api","$website/product/$id",listOf("https://images.example.test/$id.png"),"Brand $id","sku-$id","hộp","1 hộp","VND",
        price?.toBigDecimal(),null,null,null, emptyList(),0,"unknown",BigDecimal(now.epochSecond),BigDecimal(now.epochSecond),BigDecimal(now.epochSecond),false,
        listOf(ProviderCategory("food","Thực phẩm","$website/food",null)),false,emptyList())
    private inner class FakeCatalog : CatalogProvider {
        var values = emptyList<ProviderProduct>()
        var calls = 0
        var failure: ProviderFailure? = null
        var gate:CompletableDeferred<Unit>?=null
        var entered:CompletableDeferred<Unit>?=null
        override suspend fun products(query: CatalogQuery): ProviderPage {
            calls++;entered?.complete(Unit);gate?.await(); failure?.let { throw it }
            val matches = values.filter { query.site == null || it.website == query.site }
            return ProviderPage(matches.drop((query.page-1)*query.limit).take(query.limit),matches.size.toLong(),query.page,query.limit,0,emptyList())
        }
    }
    @Before fun setup() = runBlocking {
        db = Database("jdbc:postgresql://127.0.0.1:55432/auth00_test","auth00_test","")
        db.migrate(); resetAuthTestData(db)
        auth = AuthService(db)
        account = auth.register("${UUID.randomUUID()}@example.com","Catalog test password!","Catalog test password!",null)
        provider.values = listOf(item("a"),item("b",price=null),item("c",price="20000"),item("other","https://two.example.test"))
        CatalogImporter(db,provider,clock).sync()
        val first = CatalogNormalizer(clock).normalize(provider.values.first())!!
        val other = CatalogNormalizer(clock).normalize(provider.values.last())!!
        retailer=first.retailer.id;category=first.categories.first().id;otherRetailer=other.retailer.id;otherCategory=other.categories.first().id
        products=ProductService(db,auth,provider,clock)
        val created=auth.shoppingPlans().create(token,"create",obj("""{"budget":"500000","currency":"VND","selections":[{"retailer_id":"$retailer"}],"category_ids":["$category"]}"""))
        planId=created.data.jsonObject.getValue("plan").jsonObject.getValue("id").jsonPrimitive.content
    }
    @After fun close() { if (::db.isInitialized) db.close() }
    private suspend fun refresh(expected: Int=1,key: String="refresh") = products.requestRefresh(token,planId,key,obj("""{"expected_version":$expected}"""))

    @Test fun contextPaginationSortingAndUnknownPricesAreCorrect(): Unit = runBlocking {
        val first=products.catalog(token,planId,CatalogFilter(sort="PRICE_ASC",limit=2))
        assertEquals(listOf("10000","20000"),first.items.map { it.price })
        assertNotNull(first.next_cursor)
        val second=products.catalog(token,planId,CatalogFilter(sort="PRICE_ASC",limit=2,cursor=first.next_cursor))
        assertNull(second.items.single().price)
        assertNull(second.next_cursor)
        assertEquals(CatalogStatus.PARTIAL,second.status)
        assertEquals("20000",products.catalog(token,planId,CatalogFilter(sort="PRICE_DESC")).items.first().price)
        val search=products.catalog(token,planId,CatalogFilter(query="Mặt hàng c"))
        assertEquals(1,search.items.size)
        assertTrue(products.catalog(token,planId,CatalogFilter(query="%notfound")).items.isEmpty())
        assertEquals(CatalogStatus.EMPTY,products.catalog(token,planId,CatalogFilter(query="%notfound")).status)
        assertTrue(first.items.all { it.retailer_id==retailer && it.product!!.category_id==category })
        assertEquals(listOf(retailer),auth.shoppingProfiles().references(token,"categories",null,null,100,null).items.first {it.id==category}.retailer_ids)
    }
    @Test fun ownerFiltersAndProductDetailsNeverEscapePlan(): Unit = runBlocking {
        val stranger=auth.register("${UUID.randomUUID()}@example.com","Other test password!","Other test password!",null)
        assertEquals(404,assertFailsWith<ApiFailure> { products.catalog(stranger.tokens.access_token,planId,CatalogFilter()) }.status)
        assertEquals(422,assertFailsWith<ApiFailure> { products.catalog(token,planId,CatalogFilter(retailer=otherRetailer)) }.status)
        assertEquals(422,assertFailsWith<ApiFailure> { products.catalog(token,planId,CatalogFilter(category=otherCategory)) }.status)
        val first=products.catalog(token,planId,CatalogFilter()).items.first()
        assertEquals(first.product,products.detail(token,planId,first.product!!.id).product)
        val foreign=CatalogNormalizer(clock).normalize(provider.values.last())!!.product.id
        assertEquals(404,assertFailsWith<ApiFailure> { products.detail(token,planId,foreign) }.status)
    }
    @Test fun cursorIsBoundToOwnerContextSortAndCacheGeneration(): Unit = runBlocking {
        val page=products.catalog(token,planId,CatalogFilter(limit=1))
        assertFailsWith<ApiFailure> { products.catalog(token,planId,CatalogFilter(limit=1,sort="PRICE_ASC",cursor=page.next_cursor)) }
        CatalogImporter(db,provider,clock).sync()
        assertEquals("INVALID_CURSOR",assertFailsWith<ApiFailure> { products.catalog(token,planId,CatalogFilter(limit=1,cursor=page.next_cursor)) }.code)
        assertFailsWith<ApiFailure> { products.catalog(token,planId,CatalogFilter(sort="PRICE_ZERO")) }
    }
    @Test fun refreshIsIdempotentAndDoesNotBlockDraftTransaction(): Unit = runBlocking {
        val accepted=refresh()
        assertEquals(202,accepted.status)
        assertEquals(accepted,refresh())
        assertEquals("CATALOG_REFRESH_IN_PROGRESS",assertFailsWith<ApiFailure> { refresh(2,"another") }.code)
        assertEquals(CatalogStatus.LOADING,products.catalog(token,planId,CatalogFilter()).status)
        val ticket=products.ticket(accepted)
        products.refresh(ticket);val count=provider.calls;products.refresh(ticket)
        assertEquals(count,provider.calls)
        val saved=auth.shoppingPlans().get(token,planId).plan
        assertEquals(3,saved.version)
        assertEquals(PlanStatus.BROWSING_PRODUCTS,saved.status)
        assertEquals(0,saved.revision)
        assertNotEquals(CatalogStatus.LOADING,saved.catalog_status)
        assertEquals("VERSION_CONFLICT",assertFailsWith<ApiFailure> { refresh(1,"outdated") }.code)
    }
    @Test fun providerFailureKeepsCacheAndSnapshotAndHonorsRetryAfter(): Unit = runBlocking {
        products.refresh(products.ticket(refresh()))
        val offer=products.catalog(token,planId,CatalogFilter()).items.first { it.price!=null }
        val version=auth.shoppingPlans().get(token,planId).plan.version
        ShoppingData(db,auth,clock).mutate(token,MutationRequest("POST","/plans/$planId/items","add",JsonObject(emptyMap()))) {
            val view=addItem(UUID.fromString(planId),version,ItemInput("1",offer.ref,offer.version))
            MutationResult(201,shoppingJson.encodeToJsonElement(view))
        }
        val before=auth.shoppingPlans().get(token,planId)
        db.transaction { c -> execute(c,"UPDATE catalog_sources SET fetched_at=?",now.minusSeconds(901)) }
        provider.failure=ProviderFailure(ProviderFailureCode.RATE_LIMITED,true,60,429)
        products.refresh(products.ticket(refresh(before.plan.version,"failure")))
        val cached=products.catalog(token,planId,CatalogFilter())
        assertEquals(CatalogStatus.PARTIAL,cached.status)
        assertEquals("PROVIDER_RATE_LIMITED",cached.retailer_results.single().error_code)
        assertEquals(60,cached.retailer_results.single().retry_after_seconds)
        assertTrue(cached.items.all { it.freshness==Freshness.STALE })
        assertEquals(before.items,auth.shoppingPlans().get(token,planId).items)
        val calls=provider.calls
        val next=auth.shoppingPlans().get(token,planId).plan.version
        products.refresh(products.ticket(refresh(next,"rate-limited-again")))
        assertEquals(calls,provider.calls)
    }
    @Test fun importIsStableAndPreservesNullAndExactMoney(): Unit = runBlocking {
        val normalizer=CatalogNormalizer(clock)
        val large=item("large",price="9007199254740993")
        val record=normalizer.normalize(large)!!
        assertEquals("9007199254740993",record.offer.price)
        assertEquals(record,normalizer.normalize(large))
        assertEquals(PriceScope.RETAILER,record.offer.price_scope)
        assertEquals(StockStatus.UNKNOWN,record.offer.stock_status)
        val ambiguous=normalizer.normalize(large.copy(priceOptions=listOf(ProviderPriceOption(BigDecimal.ONE,"1"),ProviderPriceOption(BigDecimal.TEN,"2"))))!!
        assertNull(ambiguous.offer.price)
        assertNull(normalizer.normalize(large.copy(currency="USD")))
        assertNull(normalizer.normalize(large.copy(unit=null)))
        assertNull(normalizer.normalize(large.copy(categories=emptyList())))
        val missing=normalizer.normalize(large.copy(observedAt=null))!!
        assertNull(missing.offer.price);assertEquals(Freshness.UNKNOWN,missing.offer.freshness)
        assertNull(normalizer.normalize(large.copy(observedAt=BigDecimal("1793110000123")))!!.offer.price)
        val scoped=normalizer.normalize(large.copy(hasStoreMetadata=true))!!
        assertNull(scoped.offer.price);assertTrue(scoped.offer.warnings.any {it.code=="STORE_PRICE_SCOPE_UNVERIFIED"})
        provider.values=listOf(large)
        val imported=CatalogImporter(db,provider,clock).sync()
        assertEquals(1,imported.imported)
        assertEquals(1,products.catalog(token,planId,CatalogFilter()).items.size)
    }
    @Test fun incompleteOrFailedImportDoesNotEraseGoodCache(): Unit = runBlocking {
        provider.values=provider.values+item("invalid").copy(currency="USD")
        assertTrue(CatalogImporter(db,provider,clock).sync().partial)
        assertEquals(3,products.catalog(token,planId,CatalogFilter()).items.size)
        provider.failure=ProviderFailure(ProviderFailureCode.NETWORK,true)
        assertFailsWith<ProviderFailure> { CatalogImporter(db,provider,clock).sync() }
        assertEquals(3,products.catalog(token,planId,CatalogFilter()).items.size)
    }
    @Test fun oldCachePricesRequireScopeRevalidationBeforeBecomingKnownAgain():Unit=runBlocking {
        db.transaction {c->execute(c,"UPDATE shopping_offers SET payload=jsonb_set(payload,'{version}','\"legacy-price\"'::jsonb) WHERE kind='CATALOG'")}
        val legacy=products.catalog(token,planId,CatalogFilter(sort="PRICE_ASC"))
        assertTrue(legacy.items.all {it.price==null && it.warnings.any {w->w.code=="STORE_PRICE_SCOPE_UNVERIFIED"}})
        products.refresh(products.ticket(refresh()))
        assertTrue(products.catalog(token,planId,CatalogFilter()).items.any {it.price!=null && it.version.startsWith("cf_price2_")})
    }
    @Test fun branchFilterDoesNotPretendChainPriceIsBranchVerified(): Unit = runBlocking {
        seedPlanReferences(db)
        val store=StoreSnapshot("catalog-test-branch",1,retailer,"Chi nhánh test",null,Coordinate(10.0,106.0),"TEST","PUBLIC")
        db.transaction { c -> execute(c,"INSERT INTO shopping_stores(id,retailer_id,visibility,payload) VALUES (?,?,'PUBLIC',?::jsonb)",store.id,retailer,Json.encodeToString(store)) }
        val result=products.catalog(token,planId,CatalogFilter(store=store.id))
        assertTrue(result.items.all { it.store_id==null && it.warnings.any { w -> w.code=="RETAILER_PRICE_NOT_STORE_CONFIRMED" } })
    }
    @Test fun budgetEditDuringNetworkWorkPreservesItemsAndDoesNotLeaveLoadingStuck():Unit=runBlocking {
        products.refresh(products.ticket(refresh()))
        val offer=products.catalog(token,planId,CatalogFilter()).items.first {it.price!=null}
        ShoppingData(db,auth,clock).mutate(token,MutationRequest("POST","/plans/$planId/items","concurrent-add",JsonObject(emptyMap()))) {
            val added=addItem(UUID.fromString(planId),3,ItemInput("1",offer.ref,offer.version));MutationResult(201,shoppingJson.encodeToJsonElement(added))
        }
        val before=auth.shoppingPlans().get(token,planId)
        db.transaction {c->execute(c,"UPDATE catalog_sources SET fetched_at=?",now.minusSeconds(901))}
        val ticket=products.ticket(refresh(before.plan.version,"concurrent-refresh"))
        provider.gate=CompletableDeferred();provider.entered=CompletableDeferred()
        val worker=async {products.refresh(ticket)}
        withTimeout(5000){provider.entered!!.await()}
        val edited=auth.shoppingPlans().patch(token,planId,"budget-during-refresh",obj("""{"expected_version":${ticket.version},"budget":"600000"}"""))
        assertNotNull(edited)
        assertEquals(ticket,products.pending(token,planId))
        provider.gate!!.complete(Unit);withTimeout(10000){worker.await()}
        val after=auth.shoppingPlans().get(token,planId)
        assertEquals("600000",after.plan.budget);assertEquals(before.items,after.items)
        assertEquals(CatalogStatus.FAILED,after.plan.catalog_status);assertNull(products.pending(token,planId))
    }
    @Test fun branchPlanAddsChainOfferOverHttpAndDeleteRemovesUnfinalizedPlan(): Unit = runBlocking {
        val store=StoreSnapshot("catalog-cart-branch",1,retailer,"Chi nhánh giỏ hàng",null,Coordinate(10.0,106.0),"TEST","PUBLIC")
        db.transaction { c -> execute(c,"INSERT INTO shopping_stores(id,retailer_id,visibility,payload) VALUES (?,?,'PUBLIC',?::jsonb)",store.id,retailer,Json.encodeToString(store)) }
        val branchPlan=auth.shoppingPlans().create(token,"branch-plan",obj("""{"budget":"500000","currency":"VND","selections":[{"store_id":"${store.id}"}],"category_ids":["$category"]}"""))
            .data.jsonObject.getValue("plan").jsonObject.getValue("id").jsonPrimitive.content
        val data=ShoppingData(db,auth,clock)
        for ((version,state) in listOf(1 to CatalogStatus.LOADING,2 to CatalogStatus.READY))
            data.mutate(token,MutationRequest("POST","/plans/$branchPlan/test","state-$version",JsonObject(emptyMap()))) { MutationResult(200,shoppingJson.encodeToJsonElement(catalogState(UUID.fromString(branchPlan),version,state))) }
        val offer=products.catalog(token,branchPlan,CatalogFilter()).items.first { it.price!=null }
        assertNull(offer.store_id)
        testApplication {
            application { authModule(auth,catalog=provider) }
            suspend fun add(key: String,version: Int)=client.post("/api/v1/plans/$branchPlan/items") { bearerAuth(token);contentType(ContentType.Application.Json);header("Idempotency-Key",key)
                setBody("""{"expected_version":$version,"quantity":"1","offer_ref":{"kind":"CATALOG","id":"${offer.ref.id}"},"expected_offer_version":"${offer.version}"}""") }
            val added=add("cart-add",3)
            assertEquals(HttpStatusCode.Created,added.status)
            val view=Json.parseToJsonElement(added.bodyAsText()).jsonObject.getValue("data").jsonObject
            assertEquals(offer.product!!.id,view.getValue("items").jsonArray.single().jsonObject.getValue("product_id").jsonPrimitive.content)
            assertEquals(offer.price,view.getValue("plan").jsonObject.getValue("budget_summary").jsonObject.getValue("selected_total").jsonPrimitive.content)
            assertEquals(HttpStatusCode.Conflict,add("cart-duplicate",4).status)
            suspend fun delete(version: String,key: String)=client.delete("/api/v1/plans/$branchPlan?expected_version=$version") { bearerAuth(token);header("Idempotency-Key",key) }
            assertEquals(HttpStatusCode.Conflict,delete("3","stale-delete").status)
            assertEquals(HttpStatusCode.NoContent,delete("4","delete").status)
            assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/plans/$branchPlan") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound,delete("4","delete").status)
        }
        assertEquals(listOf(planId),auth.shoppingPlans().list(token,null,20,null).items.map { it.id })
        db.transaction { c -> query(c,"SELECT count(*) FILTER (WHERE action='PLAN_DELETED'),count(*) FROM shopping_audit WHERE plan_id=?",UUID.fromString(branchPlan)).use { s -> s.executeQuery().use { r ->
            r.next();assertEquals(1,r.getInt(1));assertTrue(r.getInt(2)>1,"earlier audit rows stay append-only")
        } } }
    }
    @Test fun offerListedThroughSecondaryCategoryCanBeAdded(): Unit = runBlocking {
        val base=item("multi")
        provider.values=provider.values+base.copy(categories=listOf(ProviderCategory("pork","Thịt heo","https://one.example.test/pork",null))+base.categories)
        CatalogImporter(db,provider,clock).sync()
        products.refresh(products.ticket(refresh()))
        val offer=products.catalog(token,planId,CatalogFilter(query="Mặt hàng multi")).items.single()
        assertNotEquals(category,offer.product!!.category_id,"primary category is outside the Plan; it is listed via its second one")
        fun add(key: String)=auth.shoppingPlans().addItem(token,planId,key,obj("""{"expected_version":${auth.shoppingPlans().get(token,planId).plan.version},"quantity":"1","offer_ref":{"kind":"CATALOG","id":"${offer.ref.id}"},"expected_offer_version":"${offer.version}"}"""))
        assertEquals(201,add("multi-add").status)
        // Without any overlapping entry category the same offer is outside the Plan context.
        db.transaction { c ->
            execute(c,"DELETE FROM shopping_items WHERE plan_id=?",UUID.fromString(planId))
            execute(c,"UPDATE catalog_entries SET category_ids=ARRAY[?::text] WHERE offer_id=?",offer.product!!.category_id,offer.ref.id)
        }
        assertEquals("VALIDATION_ERROR",assertFailsWith<ApiFailure> { add("outside-add") }.code)
    }
    @Test fun listEditReviewFinalizeAndReopenOverHttp(): Unit = runBlocking {
        products.refresh(products.ticket(refresh()))
        val offer=products.catalog(token,planId,CatalogFilter()).items.first { it.price!=null }
        testApplication {
            application { authModule(auth,catalog=provider) }
            suspend fun send(method: HttpMethod,path: String,body: String?=null)=client.request("/api/v1/plans/$planId$path") {
                this.method=method;bearerAuth(token);header("Idempotency-Key",UUID.randomUUID().toString())
                if(body!=null) { contentType(ContentType.Application.Json);setBody(body) }
            }
            suspend fun data(response: HttpResponse)=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
            suspend fun code(response: HttpResponse)=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
            fun version(view: JsonObject)=view.getValue("plan").jsonObject.getValue("version").jsonPrimitive.int
            var view=data(send(HttpMethod.Post,"/items","""{"expected_version":3,"quantity":"1","offer_ref":{"kind":"CATALOG","id":"${offer.ref.id}"},"expected_offer_version":"${offer.version}"}"""))
            val itemId=view.getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            view=data(send(HttpMethod.Patch,"/items/$itemId","""{"expected_version":${version(view)},"quantity":"2","priority":"ESSENTIAL"}"""))
            val item=view.getValue("items").jsonArray.single().jsonObject
            assertEquals("2",item.getValue("quantity").jsonPrimitive.content);assertEquals("ESSENTIAL",item.getValue("priority").jsonPrimitive.content)
            assertEquals(HttpStatusCode.UnprocessableEntity,send(HttpMethod.Patch,"/items/$itemId","""{"expected_version":${version(view)},"priority":"MAYBE"}""").status)
            assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED",code(send(HttpMethod.Delete,"/items/$itemId?expected_version=${version(view)}")))
            // The catalog price moves after the item was added: review shows it, finalize needs explicit confirmation.
            db.transaction { c -> execute(c,"UPDATE shopping_offers SET payload=jsonb_set(jsonb_set(payload,'{price}','\"15000\"'),'{version}','\"cf_price2_moved\"') WHERE kind='CATALOG' AND id=?",offer.ref.id) }
            val reviewResponse=send(HttpMethod.Post,"/finalization-reviews","""{"expected_version":${version(view)}}""")
            assertEquals(HttpStatusCode.Created,reviewResponse.status)
            val review=data(reviewResponse)
            assertEquals(listOf(itemId),review.getValue("price_changed_item_ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("30000",review.getValue("view").jsonObject.getValue("plan").jsonObject.getValue("budget_summary").jsonObject.getValue("selected_total").jsonPrimitive.content)
            val reviewId=review.getValue("review_id").jsonPrimitive.content
            assertEquals("PRICE_CONFIRMATION_REQUIRED",code(send(HttpMethod.Post,"/finalize","""{"expected_version":${version(view)},"review_id":"$reviewId","confirm_price_changes":false}""")))
            assertEquals("PRICE_CHANGED",code(send(HttpMethod.Post,"/finalize","""{"expected_version":${version(view)},"review_id":"stale","confirm_price_changes":true}""")))
            val finalized=data(send(HttpMethod.Post,"/finalize","""{"expected_version":${version(view)},"review_id":"$reviewId","confirm_price_changes":true}"""))
            assertEquals(1,finalized.getValue("revision").jsonPrimitive.int)
            assertEquals("FINALIZED",finalized.getValue("plan").jsonObject.getValue("status").jsonPrimitive.content)
            view=data(client.get("/api/v1/plans/$planId") { bearerAuth(token) })
            assertEquals("15000",view.getValue("items").jsonArray.single().jsonObject.getValue("selected_price_snapshot").jsonObject.getValue("price").jsonPrimitive.content)
            assertEquals("INVALID_STATE",code(send(HttpMethod.Patch,"/items/$itemId","""{"expected_version":${version(view)},"quantity":"3"}""")))
            view=data(send(HttpMethod.Post,"/reopen","""{"expected_version":${version(view)}}"""))
            assertEquals("BUILDING_LIST",view.getValue("plan").jsonObject.getValue("status").jsonPrimitive.content)
            view=data(send(HttpMethod.Delete,"/items/$itemId?expected_version=${version(view)}&confirm_essential=true"))
            assertTrue(view.getValue("items").jsonArray.isEmpty())
            assertEquals("EMPTY_LIST",code(send(HttpMethod.Post,"/finalization-reviews","""{"expected_version":${version(view)}}""")))
        }
    }
    @Test fun httpRoutesRequireAuthAndReturnCatalogContracts(): Unit = runBlocking {
        testApplication {
            application { authModule(auth,catalog=provider) }
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/plans/$planId/catalog").status)
            val catalog=client.get("/api/v1/plans/$planId/catalog?limit=2&sort=PRICE_ASC") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK,catalog.status)
            val data=Json.parseToJsonElement(catalog.bodyAsText()).jsonObject.getValue("data").jsonObject
            assertEquals(2,data.getValue("items").jsonArray.size)
            val id=data.getValue("items").jsonArray.first().jsonObject.getValue("product").jsonObject.getValue("id").jsonPrimitive.content
            assertEquals(HttpStatusCode.OK,client.get("/api/v1/products/$id?plan_id=$planId") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.UnprocessableEntity,client.get("/api/v1/products/$id") { bearerAuth(token) }.status)
            val refresh=client.post("/api/v1/plans/$planId/catalog-refresh") { bearerAuth(token);contentType(ContentType.Application.Json);header("Idempotency-Key","http-refresh");setBody("""{"expected_version":1}""") }
            assertEquals(HttpStatusCode.Accepted,refresh.status)
        }
    }
}
