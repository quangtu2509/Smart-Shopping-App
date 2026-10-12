package com.toked.smartshopping.backend

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthPostmanTest {
    @Test fun postmanCollectionAgainstRealHttpAndPostgres() = runBlocking(Dispatchers.IO) {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url == "jdbc:postgresql://127.0.0.1:55432/auth00_test")
        Database(url,"auth00_test","").use { database ->
            database.migrate()
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { authModule(AuthService(database)) }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                val probe = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:$port/api/v1/me")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding())
                assertEquals(401,probe.statusCode(),"Netty listener must answer before Newman starts")
                val output = File("build/postman-local.log")
                val builder = ProcessBuilder("cmd.exe","/c","npx.cmd","--offline","--yes","newman@6.2.1","run",
                    "postman/auth00.postman_collection.json","--env-var","base_url=http://127.0.0.1:$port/api/v1",
                    "--reporters","cli","--reporter-cli-no-console","--reporter-cli-no-banner",
                    "--reporter-cli-no-failures","--reporter-cli-no-assertions","--timeout-request","10000")
                    .redirectErrorStream(true).redirectOutput(output)
                builder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
                builder.environment()["no_proxy"] = "127.0.0.1,localhost"
                val process = builder.start()
                val finished = process.waitFor(180,TimeUnit.SECONDS)
                if (!finished) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
                assertTrue(finished,"Newman timed out; inspect local output")
                assertEquals(0,process.exitValue(),"Postman assertions failed; inspect local output without exporting credentials")
            } finally { server.stop(500,2000) }
        }
    }
}
