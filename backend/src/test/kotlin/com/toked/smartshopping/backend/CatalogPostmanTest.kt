package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.catalog.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CatalogPostmanTest {
    @Test fun collectionAgainstIsolatedNettyAndPostgres(): Unit=runBlocking(Dispatchers.IO) {
        Database("jdbc:postgresql://127.0.0.1:55432/auth00_test","auth00_test","").use {db ->
            db.migrate();resetAuthTestData(db)
            val auth=AuthService(db);val provider=FixtureCatalogProvider()
            CatalogImporter(db,provider).sync()
            fun account()=auth.register("${UUID.randomUUID()}@example.com","Catalog Postman password!","Catalog Postman password!",null)
            val first=account();val other=account()
            val record=CatalogNormalizer(Clock.systemUTC()).normalize(provider.values.first())!!
            val foreign=CatalogNormalizer(Clock.systemUTC()).normalize(provider.values.last())!!
            val body=Json.parseToJsonElement("""{"budget":"500000","currency":"VND","selections":[{"retailer_id":"${record.retailer.id}"}],"category_ids":["${record.categories.first().id}"]}""").jsonObject
            val created=auth.shoppingPlans().create(first.tokens.access_token,"postman-create",body)
            val planId=created.data.jsonObject.getValue("plan").jsonObject.getValue("id").jsonPrimitive.content
            val server=embeddedServer(Netty,host="127.0.0.1",port=0){authModule(auth,catalog=provider)}.start(wait=false)
            val environment=File.createTempFile("catalog-newman-",".json",File("build"))
            try {
                val port=server.engine.resolvedConnectors().first().port
                val variables=mapOf("base_url" to "http://127.0.0.1:$port/api/v1","access_token" to first.tokens.access_token,
                    "other_token" to other.tokens.access_token,"plan_id" to planId,"foreign_retailer" to foreign.retailer.id,
                    "foreign_category" to foreign.categories.first().id,"refresh_key" to UUID.randomUUID().toString())
                environment.writeText(buildJsonObject {put("values",JsonArray(variables.map {(key,value)->buildJsonObject {put("key",key);put("value",value);put("enabled",true)}}))}.toString())
                val process=ProcessBuilder("cmd.exe","/c","npx.cmd","--offline","--yes","newman@6.2.1","run","postman/catalog01.postman_collection.json","-e",environment.absolutePath,
                    "--reporters","cli","--reporter-cli-no-console","--reporter-cli-no-banner","--reporter-cli-no-failures","--reporter-cli-no-assertions","--timeout-request","10000")
                    .redirectErrorStream(true).redirectOutput(File("build/catalog-postman-local.log")).apply {environment()["NO_PROXY"]="127.0.0.1,localhost";environment()["no_proxy"]="127.0.0.1,localhost"}.start()
                val finished=process.waitFor(120,TimeUnit.SECONDS)
                if(!finished){process.descendants().forEach {it.destroyForcibly()};process.destroyForcibly()}
                assertTrue(finished,"Catalog Newman timed out");assertEquals(0,process.exitValue(),"Catalog Postman assertions failed")
            } finally {environment.delete();server.stop(500,2000)}
        }
    }
}
