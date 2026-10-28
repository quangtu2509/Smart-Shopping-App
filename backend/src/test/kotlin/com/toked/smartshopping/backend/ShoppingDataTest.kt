package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.shopping.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ShoppingDataTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var data: ShoppingData
    private lateinit var account: AuthResult
    private lateinit var stranger: AuthResult
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val clock = Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"),ZoneOffset.UTC)
    private val product = Product("data-product","Original product","data-category","kg","0.1",package_size="1")
    private fun offer(price: String? = "20", version: String = "offer-1", scope: PriceScope = PriceScope.RETAILER) = Offer(
        OfferRef(OfferKind.CATALOG,"same-id"),version,product=product,retailer_id="data-retailer",price=price,currency="VND",
        source="TEST_FIXTURE",observed_at="2026-10-01T00:00:00Z",price_scope=scope,freshness=Freshness.FRESH)
    @Before fun setup() {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url=="jdbc:postgresql://127.0.0.1:55432/auth00_test")
        db=Database(url,"auth00_test","");db.migrate();resetAuthTestData(db)
        auth=AuthService(db,clock=clock); data=ShoppingData(db,auth,clock, mapOf("kg" to "0.1"))
        fun account(email: String) = auth.register(email,"Data test password!","Data test password!",null)
        account=account("data-owner@example.com");stranger=account("data-stranger@example.com")
        db.transaction { c ->
            execute(c,"INSERT INTO shopping_categories(id,name,status) VALUES ('data-category','Data fixture category','ACTIVE') ON CONFLICT DO NOTHING")
            execute(c,"INSERT INTO shopping_retailers(id,name,status) VALUES ('data-retailer','Fixture retailer','ACTIVE'),('outside-retailer','Outside','ACTIVE')")
            execute(c,"INSERT INTO shopping_products(id,category_id,payload) VALUES (?, ?, ?::jsonb)",product.id,product.category_id,json.encodeToString(product))
            val store = StoreSnapshot("data-private",1,null,"Original private store","Fixture address",Coordinate(10.0,106.0),"USER","PRIVATE")
            execute(c,"INSERT INTO shopping_stores(id,owner_id,visibility,payload) VALUES (?,?,'PRIVATE',?::jsonb)",store.id,UUID.fromString(account.user.id),json.encodeToString(store))
            execute(c,"INSERT INTO shopping_store_products(id,store_id,custom_name,category_id,unit,quantity_step) VALUES ('private-product','data-private','Private item','data-category','piece',1)")
        }
        saveOffer(offer())
        val privateOffer = Offer(OfferRef(OfferKind.PRIVATE,"same-id"),"private-1",store_product_id="private-product",store_id="data-private",price="7",currency="VND",
            source="USER",observed_at="2026-10-01T00:00:00Z",price_scope=PriceScope.USER_ENTERED,freshness=Freshness.FRESH)
        db.transaction { c -> execute(c,"INSERT INTO shopping_offers(kind,id,store_id,store_product_id,currency,payload) VALUES ('PRIVATE','same-id','data-private','private-product','VND',?::jsonb)",json.encodeToString(privateOffer)) }
    }
    @After fun close() { if (::db.isInitialized) db.close() }
    private fun saveOffer(offer: Offer) = db.transaction { c -> execute(c,"""INSERT INTO shopping_offers(kind,id,product_id,retailer_id,currency,payload)
        VALUES ('CATALOG','same-id','data-product','data-retailer','VND',?::jsonb) ON CONFLICT(kind,id) DO UPDATE SET payload=EXCLUDED.payload""",json.encodeToString(offer)) }
    private fun mutate(path: String, body: JsonElement = buildJsonObject {}, key: String = UUID.randomUUID().toString(), access: String = account.tokens.access_token,
        action: ShoppingWrite.()->JsonElement): JsonElement = data.mutate(access,MutationRequest("POST",path,key,body)) { MutationResult(200,action()) }.data
    private fun change(id: UUID, access: String = account.tokens.access_token, action: ShoppingWrite.()->PlanView): PlanView = json.decodeFromJsonElement(mutate("/plans/$id",access=access) { json.encodeToJsonElement(action()) })
    private fun create(selections: List<Selection> = listOf(Selection(retailer_id="data-retailer")), access: String = account.tokens.access_token): PlanView =
        json.decodeFromJsonElement(mutate("/plans",access=access) { json.encodeToJsonElement(createDraft(DraftInput("100","VND",selections,listOf("data-category")))) })
    private fun ready(view: PlanView, access: String = account.tokens.access_token): PlanView {
        val id=UUID.fromString(view.plan.id)
        val loading=change(id,access) { catalogState(id,view.plan.version,CatalogStatus.LOADING) }
        return change(id,access) { catalogState(id,loading.plan.version,CatalogStatus.READY) }
    }
    private fun add(view: PlanView, input: ItemInput = ItemInput("1.5",OfferRef(OfferKind.CATALOG,"same-id"),"offer-1")): PlanView {
        val id=UUID.fromString(view.plan.id);return change(id) { addItem(id,view.plan.version,input) }
    }
    private fun failure(code: String, block: ()->Unit) = assertFailsWith<ApiFailure>(block=block).also { assertEquals(code,it.code) }

    @Test fun draftDefaultsAndProfileChangesDoNotMutateExistingPlan() {
        val plan=create(); assertEquals(1,plan.plan.version);assertEquals(0,plan.plan.revision);assertEquals(PlanStatus.DRAFT,plan.plan.status)
        assertEquals(3,plan.plan.max_stores);assertTrue(plan.plan.budget_summary.is_complete)
        db.transaction { execute(it,"UPDATE profiles SET max_stores=1 WHERE user_id=?",UUID.fromString(account.user.id)) }
        assertEquals(3,data.read(account.tokens.access_token) { plan(UUID.fromString(plan.plan.id)) }.plan.max_stores)
        assertEquals(1,create().plan.max_stores)
    }
    @Test fun selectedAndCustomItemsKeepUnknownSeparateFromConfirmedZero() {
        val known=add(ready(create()))
        assertEquals("30",known.plan.budget_summary.selected_total)
        val partial=add(known,ItemInput("1",custom_name="Unknown item",unit="piece"))
        assertFalse(partial.plan.budget_summary.is_complete);assertEquals("30",partial.plan.budget_summary.selected_total)
        assertNull(partial.items.find { it.custom_name=="Unknown item" }!!.subtotal)
        saveOffer(offer(price="0",version="free"))
        val free=add(ready(create()),ItemInput("1",OfferRef(OfferKind.CATALOG,"same-id"),"free"))
        assertEquals("0",free.items.single().subtotal);assertTrue(free.plan.budget_summary.is_complete)
    }
    @Test fun validatesContextMetadataCurrencyQuantityAndDuplicatesAtomically() {
        var p=ready(create()); val id=UUID.fromString(p.plan.id)
        failure("VALIDATION_ERROR") { add(p,ItemInput("0.15",OfferRef(OfferKind.CATALOG,"same-id"),"offer-1")) }
        failure("PRICE_CHANGED") { add(p,ItemInput("1",OfferRef(OfferKind.CATALOG,"same-id"),"stale")) }
        saveOffer(offer().copy(source=null,observed_at=null))
        failure("OFFER_METADATA_INCOMPLETE") { add(p) }
        saveOffer(offer().copy(stock_status=StockStatus.OUT_OF_STOCK));failure("OFFER_UNAVAILABLE") { add(p) }
        saveOffer(offer());p=add(p)
        failure("DUPLICATE_ITEM") { add(p) }
        assertEquals(p.plan.version,data.read(account.tokens.access_token) { plan(id) }.plan.version)
        val outside=ready(create(listOf(Selection(retailer_id="outside-retailer"))))
        failure("VALIDATION_ERROR") { add(outside) }
    }
    @Test fun privateOfferIdentityOwnershipAndStoreSnapshotAreExplicit() {
        val private=ready(create(listOf(Selection(store_id="data-private"))))
        val added=add(private,ItemInput("2",OfferRef(OfferKind.PRIVATE,"same-id"),"private-1"))
        assertEquals("14",added.plan.budget_summary.selected_total);assertEquals("Private item",added.items.single().custom_name)
        assertEquals(OfferKind.PRIVATE,added.items.single().offer_ref!!.kind)
        failure("RESOURCE_NOT_FOUND") { create(listOf(Selection(store_id="data-private")),stranger.tokens.access_token) }
        val strangerPlan=ready(create(access=stranger.tokens.access_token),stranger.tokens.access_token)
        val id=UUID.fromString(strangerPlan.plan.id)
        failure("RESOURCE_NOT_FOUND") { mutate("/plans/$id",access=stranger.tokens.access_token) { json.encodeToJsonElement(addItem(id,strangerPlan.plan.version,ItemInput("1",OfferRef(OfferKind.PRIVATE,"same-id"),"private-1"))) } }
    }
    @Test fun ownerChildAndVersionChecksPrecedeMutationOrReplay() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id);val other=add(ready(create()));val otherItem=UUID.fromString(other.items.single().id)
        failure("RESOURCE_NOT_FOUND") { data.read(stranger.tokens.access_token) { plan(id) } }
        failure("RESOURCE_NOT_FOUND") { change(id) { changeItem(id,otherItem,p.plan.version,"2") } }
        val conflict=failure("VERSION_CONFLICT") { change(id) { changeBudget(id,p.plan.version-1,"50") } }
        assertEquals(p.plan.version,conflict.details["current_version"]!!.jsonPrimitive.int)
        failure("INVALID_STATE") { add(create()) }
    }
    @Test fun replayIsAtomicCanonicalOwnerMethodPathAndPayloadScoped() {
        val input=DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category"))
        val key=UUID.randomUUID().toString();val body=json.encodeToJsonElement(input)
        val request=MutationRequest("POST","/plans",key,body)
        val first=data.mutate(account.tokens.access_token,request) { MutationResult(201,json.encodeToJsonElement(createDraft(input)),"/location") }
        val reversed=JsonObject(body.jsonObject.toList().reversed().toMap())
        val replay=data.mutate(account.tokens.access_token,request.copy(body=reversed)) { error("Replay must not execute") }
        assertEquals(first,replay)
        failure("IDEMPOTENCY_KEY_REUSED") { data.mutate(account.tokens.access_token,request.copy(body=buildJsonObject { put("different",true) })) { error("No mutation") } }
        failure("IDEMPOTENCY_KEY_REUSED") { data.mutate(account.tokens.access_token,request.copy(query=buildJsonObject { put("different",true) })) { error("No mutation") } }
        assertEquals(JsonPrimitive("method"),data.mutate(account.tokens.access_token,request.copy(method="PATCH")) { MutationResult(200,JsonPrimitive("method")) }.data)
        assertEquals(JsonPrimitive("path"),data.mutate(account.tokens.access_token,request.copy(path="/different-scope")) { MutationResult(200,JsonPrimitive("path")) }.data)
        val other=data.mutate(stranger.tokens.access_token,request) { MutationResult(201,json.encodeToJsonElement(createDraft(input))) }
        assertNotEquals(first.data.jsonObject["plan"]!!.jsonObject["id"],other.data.jsonObject["plan"]!!.jsonObject["id"])
        db.transaction { c -> query(c,"SELECT request_hash FROM shopping_mutations WHERE request_key=?",key).use { s -> s.executeQuery().use { r ->
            assertTrue(r.next());assertTrue(r.getString(1).matches(Regex("[0-9a-f]{64}")))
        } } }
    }
    @Test fun failedTransactionDoesNotConsumeKeyOrLeavePlanItemsAudit() {
        val key=UUID.randomUUID().toString();val request=MutationRequest("POST","/plans",key,buildJsonObject {})
        assertFailsWith<IllegalStateException> { data.mutate(account.tokens.access_token,request) {
            createDraft(DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")));error("Injected failure")
        } }
        db.transaction { c -> for(table in listOf("shopping_plans","shopping_mutations","shopping_audit")) query(c,"SELECT count(*) FROM $table").use { s -> s.executeQuery().use { r ->r.next();assertEquals(0,r.getInt(1)) } } }
        data.mutate(account.tokens.access_token,request) { MutationResult(201,json.encodeToJsonElement(createDraft(DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category"))))) }
    }
    @Test fun concurrentWritersConflictAndSameKeyCommitsOnce() = runBlocking {
        val p=ready(create());val id=UUID.fromString(p.plan.id)
        val outcomes=(1..2).map { async(Dispatchers.IO) { try { change(id) { changeBudget(id,p.plan.version,"90") };"saved" } catch(e:ApiFailure) { e.code } } }.awaitAll()
        assertEquals(setOf("saved","VERSION_CONFLICT"),outcomes.toSet())
        val request=MutationRequest("POST","/plans",UUID.randomUUID().toString(),buildJsonObject {})
        val result=(1..2).map { async(Dispatchers.IO) { data.mutate(account.tokens.access_token,request) {
            MutationResult(201,json.encodeToJsonElement(createDraft(DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))))
        } } }.awaitAll()
        assertEquals(result[0],result[1])
    }
    @Test fun revisionsStayImmutableAfterCatalogDeleteReopenAndNextFinalize() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id)
        val snapshot=json.decodeFromJsonElement<RevisionSnapshot>(mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,p.plan.version)) })
        assertEquals(1,snapshot.plan.revision);assertEquals("Original product",snapshot.products.single().name)
        failure("INVALID_STATE") { change(id) { changeBudget(id,snapshot.plan.version,"200") } }
        db.transaction { c -> execute(c,"DELETE FROM shopping_offers WHERE kind='CATALOG'");execute(c,"DELETE FROM shopping_products WHERE id='data-product'") }
        assertEquals(snapshot,data.read(account.tokens.access_token) { revision(id,1) })
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"UPDATE shopping_plan_revisions SET payload=payload WHERE plan_id=?",id) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"DELETE FROM shopping_plan_revisions WHERE plan_id=?",id) } }
        val reopened=change(id) { reopen(id,snapshot.plan.version) }
        assertNull(reopened.current_finalized_revision);assertEquals(1,reopened.plan.revision)
        val edited=change(id) { changeItem(id,UUID.fromString(p.items.single().id),reopened.plan.version,"2") }
        val second=json.decodeFromJsonElement<RevisionSnapshot>(mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,edited.plan.version)) })
        assertEquals(2,second.plan.revision);assertEquals("40",second.plan.budget_summary.selected_total)
        assertEquals(snapshot,data.read(account.tokens.access_token) { revision(id,1) })
        db.transaction { c -> query(c,"SELECT status FROM shopping_revision_validity WHERE plan_id=? AND revision=1",id).use { s -> s.executeQuery().use { r ->r.next();assertEquals("INVALIDATED",r.getString(1)) } } }
    }
    @Test fun itemBudgetAuditAndArtifactsCommitTogetherWithNoOpVersionStable() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id)
        mutate("/plans/$id/artifact") { JsonPrimitive(registerArtifact(id,p.plan.version,"OPTIMIZATION","SUCCEEDED").toString()) }
        assertEquals(p.plan.version,change(id) { changeItem(id,UUID.fromString(p.items.single().id),p.plan.version,"1.50") }.plan.version)
        val next=change(id) { changeItem(id,UUID.fromString(p.items.single().id),p.plan.version,"6",Priority.ESSENTIAL) }
        assertEquals("120",next.plan.budget_summary.selected_total);assertEquals("-20",next.plan.budget_summary.remaining_budget)
        assertEquals("OVER_BUDGET",next.warnings.single().code)
        failure("ESSENTIAL_CONFIRMATION_REQUIRED") { change(id) { removeItem(id,UUID.fromString(next.items.single().id),next.plan.version) } }
        val empty=change(id) { removeItem(id,UUID.fromString(next.items.single().id),next.plan.version,true) }
        assertEquals("0",empty.plan.budget_summary.selected_total)
        db.transaction { c -> query(c,"SELECT status FROM shopping_artifacts WHERE plan_id=?",id).use { s -> s.executeQuery().use { r ->r.next();assertEquals("INVALIDATED",r.getString(1)) } } }
        failure("EMPTY_LIST") { mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,empty.plan.version)) } }
    }
    @Test fun rawSqlCannotRoundCurrencySilentlyOrCreateInvalidQuantity() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id);val item=UUID.fromString(p.items.single().id)
        for (budget in listOf("1.1","0","NaN")) assertFailsWith<java.sql.SQLException> {
            db.transaction { execute(it,"UPDATE shopping_plans SET budget=?::numeric WHERE id=?",budget,id) }
        }
        for(quantity in listOf("0","0.15","NaN")) assertFailsWith<java.sql.SQLException> {
            db.transaction { execute(it,"UPDATE shopping_items SET quantity=?::numeric WHERE id=?",quantity,item) }
        }
        assertFailsWith<java.sql.SQLException> { saveOffer(offer("1.01")) }
        assertEquals(p,data.read(account.tokens.access_token) { plan(id) })
    }
    @Test fun validatesDraftBoundsAndExpiredKeysCanRepresentANewIntent() {
        failure("VALIDATION_ERROR") { create(emptyList()) }
        failure("VALIDATION_ERROR") { create(List(4) { Selection(retailer_id="data-retailer") }) }
        failure("VALIDATION_ERROR") { create(List(2) { Selection(retailer_id="data-retailer") }) }
        failure("VALIDATION_ERROR") { mutate("/plans") { json.encodeToJsonElement(createDraft(DraftInput("1.1","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))) } }
        failure("VALIDATION_ERROR") { mutate("/plans") { json.encodeToJsonElement(createDraft(DraftInput("1","AAA",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))) } }
        failure("IDEMPOTENCY_KEY_REQUIRED") { mutate("/plans",key="") { JsonNull } }
        val key=UUID.randomUUID().toString()
        val first=mutate("/plans",key=key) { json.encodeToJsonElement(createDraft(DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))) }
        db.transaction { execute(it,"UPDATE shopping_mutations SET created_at=? WHERE request_key=?",clock.instant().minusSeconds(86401),key) }
        val next=mutate("/plans",buildJsonObject { put("new",true) },key=key) { json.encodeToJsonElement(createDraft(DraftInput("200","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))) }
        assertNotEquals(first,next)
    }
    @Test fun privateRevisionContainsStoreDescriptionInsteadOfHydratingCurrentStore() {
        val p=add(ready(create(listOf(Selection(store_id="data-private")))),ItemInput("1",OfferRef(OfferKind.PRIVATE,"same-id"),"private-1"))
        val id=UUID.fromString(p.plan.id)
        val snap=json.decodeFromJsonElement<RevisionSnapshot>(mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,p.plan.version)) })
        db.transaction { execute(it,"UPDATE shopping_stores SET payload=jsonb_set(payload,'{name}','\"Changed store\"') WHERE id='data-private'") }
        assertEquals("Original private store",data.read(account.tokens.access_token) { revision(id,1) }.stores.single().name)
        assertEquals("Original private store",snap.stores.single().name)
    }
    @Test fun sessionHistoryCommitTogetherAndAllSnapshotsAreImmutable() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id)
        val snap=json.decodeFromJsonElement<RevisionSnapshot>(mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,p.plan.version)) })
        val session=UUID.randomUUID();val owner=UUID.fromString(account.user.id)
        val item=snap.items.single()
        val initialItem=SessionItemSnapshot(UUID.randomUUID().toString(),item.id,item.product_id,snap.products.single(),item.custom_name,
            "data-category",item.offer_ref,"data-retailer",null,null,item.quantity,item.unit,item.priority,item.selected_price_snapshot,ItemOutcome.PENDING,ItemOrigin.INITIAL_PLAN)
        val initial=json.encodeToString(listOf(initialItem))
        db.transaction { execute(it,"INSERT INTO shopping_sessions(id,plan_id,plan_revision,owner_id,version,status,currency,budget,initial_items) VALUES (?,?,?,?,1,'IN_PROGRESS','VND',100,?::jsonb)",session,id,1,owner,initial) }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"UPDATE shopping_sessions SET initial_items='[]' WHERE id=?",session) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"UPDATE shopping_sessions SET status='COMPLETED',version=version+1 WHERE id=?",session) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"INSERT INTO shopping_sessions(id,plan_id,plan_revision,owner_id,version,status,currency,budget,initial_items) VALUES (?,?,?,?,1,'IN_PROGRESS','VND',100,'[]')",UUID.randomUUID(),id,1,UUID.fromString(stranger.user.id)) } }
        val historyId=UUID.randomUUID()
        val finalItems=listOf(initialItem.copy(status=ItemOutcome.PURCHASED))
        val total=MoneyRules().sessionSummary("100","VND",finalItems,purchasedOnly=true)
        val history=json.encodeToJsonElement(HistorySnapshot(historyId.toString(),session.toString(),id.toString(),1,listOf(initialItem),finalItems,
            total.selected_total,total.is_complete,total.missing_price_item_ids,"VND",clock.instant().toString(),total.warnings))
        db.transaction { c ->
            execute(c,"INSERT INTO shopping_history(id,session_id,owner_id,payload) VALUES (?,?,?,?::jsonb)",historyId,session,owner,history.toString())
            execute(c,"UPDATE shopping_sessions SET status='COMPLETED',version=version+1 WHERE id=?",session)
        }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"INSERT INTO shopping_history(id,session_id,owner_id,payload) VALUES (?,?,?,?::jsonb)",UUID.randomUUID(),session,owner,history.toString()) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"UPDATE shopping_history SET payload=payload WHERE id=?",historyId) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"DELETE FROM shopping_history WHERE id=?",historyId) } }
        saveOffer(offer("999","changed-after-completion"))
        db.transaction { execute(it,"UPDATE shopping_products SET payload=jsonb_set(payload,'{name}','\"Changed after completion\"') WHERE id='data-product'") }
        db.transaction { c -> query(c,"SELECT payload FROM shopping_history WHERE id=?",historyId).use { s -> s.executeQuery().use { r ->r.next();assertEquals(history,json.parseToJsonElement(r.getString(1))) } } }
    }
    @Test fun replacementCannotLinkAnotherSessionAndOldItemsCannotBeDeletedOrRewritten() {
        val p=add(ready(create()));val id=UUID.fromString(p.plan.id)
        mutate("/plans/$id/data-revision") { json.encodeToJsonElement(appendRevision(id,p.plan.version)) }
        val a=UUID.randomUUID();val b=UUID.randomUUID();val old=UUID.randomUUID()
        db.transaction { c ->
            for(session in listOf(a,b)) execute(c,"INSERT INTO shopping_sessions(id,plan_id,plan_revision,owner_id,version,status,currency,budget,initial_items) VALUES (?,?,?,?,1,'CANCELLED','VND',100,'[]')",session,id,1,UUID.fromString(account.user.id))
            execute(c,"INSERT INTO shopping_session_items(id,session_id,snapshot,status,origin) VALUES (?,?,'{}','PENDING','INITIAL_PLAN')",old,a)
        }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"INSERT INTO shopping_session_items(id,session_id,snapshot,status,origin,replaces_session_item_id) VALUES (?,?,'{}','PENDING','REPLACEMENT',?)",UUID.randomUUID(),b,old) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"DELETE FROM shopping_session_items WHERE id=?",old) } }
        assertFailsWith<java.sql.SQLException> { db.transaction { execute(it,"UPDATE shopping_session_items SET snapshot='{\"changed\":true}' WHERE id=?",old) } }
        db.transaction { c ->
            execute(c,"UPDATE shopping_session_items SET status='REPLACED' WHERE id=?",old)
            execute(c,"INSERT INTO shopping_session_items(id,session_id,snapshot,status,origin,replaces_session_item_id) VALUES (?,?,'{}','PENDING','REPLACEMENT',?)",UUID.randomUUID(),a,old)
        }
    }
    @Test fun ordinaryReplayStorageRejectsCredentialResultsAndRollsBackTheMutation() {
        val request=MutationRequest("POST","/plans",UUID.randomUUID().toString(),buildJsonObject {})
        assertFailsWith<IllegalArgumentException> { data.mutate(account.tokens.access_token,request) {
            createDraft(DraftInput("100","VND",listOf(Selection(retailer_id="data-retailer")),listOf("data-category")))
            MutationResult(200,buildJsonObject { put("nested",buildJsonObject { put("access_token","fixture-only") }) })
        } }
        db.transaction { c -> query(c,"SELECT (SELECT count(*) FROM shopping_plans),(SELECT count(*) FROM shopping_mutations)").use { s -> s.executeQuery().use { r ->
            r.next();assertEquals(0,r.getInt(1));assertEquals(0,r.getInt(2))
        } } }
    }
}
