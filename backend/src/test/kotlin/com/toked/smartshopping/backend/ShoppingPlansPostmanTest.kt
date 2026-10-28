package com.toked.smartshopping.backend

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingPlansPostmanTest {
    @Test fun collectionAgainstNettyAndPostgres() = runBlocking(Dispatchers.IO) {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url == "jdbc:postgresql://127.0.0.1:55432/auth00_test")
        Database(url,"auth00_test", "").use { db ->
            db.migrate()
            val service = AuthService(db)
            fun account() = service.register("${UUID.randomUUID()}@example.com", "Plan test password!", "Plan test password!", null)
            val first = account(); val other = account()
            seedPlanReferences(db, first.user.id)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { authModule(service) }.start(wait = false)
            val environment = File.createTempFile("plan-newman-", ".json", File("build"))
            try {
                val port = server.engine.resolvedConnectors().first().port
                val variables = mapOf("base_url" to "http://127.0.0.1:$port/api/v1", "plan_token" to first.tokens.access_token,
                    "other_token" to other.tokens.access_token, "create_key" to UUID.randomUUID().toString(), "patch_key" to UUID.randomUUID().toString(), "owner_id" to first.user.id)
                environment.writeText(buildJsonObject { put("values", JsonArray(variables.map { (k,v) -> buildJsonObject { put("key",k); put("value",v); put("enabled",true) } })) }.toString())
                val process = ProcessBuilder("cmd.exe","/c","npx.cmd","--offline","--yes","newman@6.2.1","run",
                    "postman/plan01.postman_collection.json", "-e", environment.absolutePath, "--reporters","cli",
                    "--reporter-cli-no-console","--reporter-cli-no-banner","--reporter-cli-no-failures","--reporter-cli-no-assertions",
                    "--timeout-request","10000").redirectErrorStream(true).redirectOutput(File("build/plan-postman-local.log")).apply {
                        environment()["NO_PROXY"] = "127.0.0.1,localhost"; environment()["no_proxy"] = "127.0.0.1,localhost"
                    }.start()
                val finished = process.waitFor(120, TimeUnit.SECONDS)
                if (!finished) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() }
                assertTrue(finished, "Plan Newman timed out")
                assertEquals(0, process.exitValue(), "Plan Postman assertions failed")
            } finally { environment.delete(); server.stop(500, 2000) }
        }
    }
}
