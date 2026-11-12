package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.catalog.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CatalogIncrementalTest {
    private lateinit var db: Database
    private val clock=Clock.fixed(Instant.parse("2026-11-03T01:00:00Z"),ZoneOffset.UTC)
    private fun item(id: String="1", site: String="one.example.test", price: Int=10000, stores: String="[]", unit: String="\"box\""): ProviderProduct =
        CatalogFlowDecoder().page("""{"items":[{"id":"$id","name":"Milk","website":"$site","approved":true,
          "source":"listing","url":"https://$site/p/$id","currency":"VND","price":$price,"unit":$unit,
          "observed_at":1793667600,"last_success_at":1793667600,"stale":false,
          "categories":[{"id":"food","name":"Food"}],"stores":$stores}],"total":1,"page":1,"limit":50}""",CatalogQuery()).items.single()
    private class Source:IncrementalCatalogProvider {
        var dataset="generation-1";var cursor="cursor-1";var snapshotCalls=0;var changeCalls=0
        var values=emptyList<ProviderProduct>();var events=emptyList<ProviderChange>()
        var reset=false;var failure:ProviderFailure?=null
        override suspend fun products(query:CatalogQuery):ProviderPage = error("Full pagination must not be used")
        override suspend fun productSnapshot(after:Long,baseline:String?):ProviderSnapshot {
            snapshotCalls++;return ProviderSnapshot(dataset,values,values.size.toLong(),false,cursor,cursor,0)
        }
        override suspend fun productChanges(cursor:String):ProviderChanges {
            changeCalls++;failure?.let { throw it }
            if(reset) { reset=false;throw ProviderFailure(ProviderFailureCode.DATASET_RESET) }
            return ProviderChanges(dataset,events,this.cursor,false,0)
        }
    }
    @Before fun setup() {
        db=Database("jdbc:postgresql://127.0.0.1:55432/auth00_test","auth00_test","")
        db.migrate();resetAuthTestData(db)
        db.transaction { c -> execute(c,"DELETE FROM catalog_sync_state");execute(c,"DELETE FROM catalog_provider_entries") }
    }
    @After fun teardown(){ db.close() }
    private fun savedCursor()=db.transaction { c -> query(c,"SELECT cursor FROM catalog_sync_state WHERE provider='catalogflow'").use { s -> s.executeQuery().use { r -> if(r.next()) r.getString(1) else null } } }
    private fun active()=db.transaction { c -> query(c,"SELECT count(*) FROM catalog_entries WHERE active=TRUE").use { s ->s.executeQuery().use { r ->r.next();r.getInt(1) } } }

    @Test fun snapshotOnceThenOnlyChangesAndDeletes()=runBlocking {
        val source=Source().apply { values=listOf(item()) }
        val importer=CatalogImporter(db,source,clock)
        assertFalse(importer.sync().partial);assertEquals(1,active());assertEquals("cursor-1",savedCursor())
        source.cursor="cursor-2";source.events=listOf(ProviderChange("1","upsert",item(price=12000)))
        importer.sync();assertEquals(1,source.snapshotCalls);assertEquals("cursor-2",savedCursor());assertEquals(1,active())
        source.cursor="cursor-3";source.events=listOf(ProviderChange("1","delete",null))
        importer.sync();assertEquals(0,active());assertEquals("cursor-3",savedCursor())
        importer.sync();assertEquals(0,active());assertEquals(1,source.snapshotCalls)
    }

    @Test fun generationResetClearsOnlyCatalogFlowCache()=runBlocking {
        val source=Source().apply { values=listOf(item(),item("2","other.example.test")) }
        val importer=CatalogImporter(db,source,clock);importer.sync()
        db.transaction { c ->execute(c,"UPDATE shopping_retailers SET code='OTHER' WHERE id=?",CatalogNormalizer.retailerId("other.example.test")) }
        source.dataset="generation-2";source.cursor="new-cursor";source.values=emptyList();source.reset=true
        importer.sync()
        assertEquals(2,source.snapshotCalls);assertEquals(1,active());assertEquals("new-cursor",savedCursor())
    }

    @Test fun networkFailureDoesNotAdvanceCursorOrDeleteCache()=runBlocking {
        val source=Source().apply { values=listOf(item()) };val importer=CatalogImporter(db,source,clock)
        importer.sync();source.failure=ProviderFailure(ProviderFailureCode.NETWORK,retryable=true)
        assertFailsWith<ProviderFailure> { importer.sync() }
        assertEquals("cursor-1",savedCursor());assertEquals(1,active())
    }

    @Test fun decoderRequiresValidDeleteAndSnapshotEnvelope() {
        val decoder=CatalogFlowDecoder()
        val page=decoder.changes("""{"dataset_id":"g","items":[{"id":1,"operation":"delete","product":null}],"next_cursor":"c","has_more":false}""")
        assertEquals("delete",page.items.single().operation)
        assertFailsWith<ProviderFailure> { decoder.changes("""{"dataset_id":"g","items":[{"id":1,"operation":"unknown"}],"next_cursor":"c","has_more":false}""") }
        assertFailsWith<ProviderFailure> { decoder.snapshot("""{"items":[],"after":0,"has_more":false}""") }
    }
    @Test fun observationCheckpointRefreshesMetadataWithoutProductDownload()=runBlocking {
        val source=Source().apply { values=listOf(item().copy(sourceIds=listOf("source-1"))) }
        val importer=CatalogImporter(db,source,clock);importer.sync()
        db.transaction { c ->execute(c,"UPDATE shopping_offers SET payload=jsonb_set(jsonb_set(payload,'{observed_at}','\"2000-01-01T00:00:00Z\"'::jsonb),'{freshness}','\"STALE\"'::jsonb)") }
        source.cursor="cursor-2"
        source.events=listOf(ProviderChange("0","observed",null,ProviderObservation("source-1",java.math.BigDecimal(clock.instant().epochSecond))))
        importer.sync()
        val payload=db.transaction { c ->query(c,"SELECT payload::text FROM shopping_offers WHERE kind='CATALOG'").use { q ->q.executeQuery().use { r ->r.next();r.getString(1) } } }
        val obj=kotlinx.serialization.json.Json.parseToJsonElement(payload).toString()
        assertTrue(obj.contains("FRESH"));assertTrue(obj.contains("10000"))
        assertTrue(obj.contains("\"cf_price2_obs_"),"observation keeps the verified price version")
        assertEquals(1,source.snapshotCalls);assertEquals("cursor-2",savedCursor())
    }

    @Test fun branchListedCatalogFlowPriceStaysRetailerScoped() {
        val record=CatalogNormalizer(clock).normalize(item(stores="""[{"id":"1","name":"Emart Gò Vấp"}]"""))!!
        assertEquals("10000",record.offer.price);assertEquals(com.toked.smartshopping.backend.shopping.PriceScope.RETAILER,record.offer.price_scope)
        assertFalse(record.offer.warnings.any { it.code=="STORE_PRICE_SCOPE_UNVERIFIED" })
        val unitless=CatalogNormalizer(clock).normalize(item(unit="null"))
        assertEquals("sản phẩm",assertNotNull(unitless,"a listing without unit is imported, not rejected").product.unit)
    }

}
