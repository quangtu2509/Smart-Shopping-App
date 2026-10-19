package com.toked.smartshopping.backend

import org.junit.Test
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostmanScriptsTest {
    @Test fun postmanManualAndFixtureScriptsRespectTheirContracts() {
        val output = File("build/postman-script-tests.log")
        val process = ProcessBuilder("node", "--test", "src/test/javascript/postman-auth.test.cjs")
            .redirectErrorStream(true).redirectOutput(output).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "Postman script tests timed out")
        assertEquals(0, process.exitValue(), "Postman script tests failed; inspect bounded test diagnostics locally")
    }
    @Test fun missingManualResetPasswordSkipsBothHttpRequestsInNewman() {
        val received = AtomicInteger()
        val sink = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        sink.createContext("/") { exchange ->
            received.incrementAndGet()
            exchange.requestBody.close()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        val file = File("build/postman-missing-password.json")
        sink.start()
        try {
            val source = Json.parseToJsonElement(File("postman/auth00.postman_collection.json").readText()).jsonObject
            val items = source.getValue("item").jsonArray.filter {
                it.jsonObject.getValue("name").jsonPrimitive.content in setOf("API-AUTH-16 Reset password", "Reset grant replay rejected")
            }
            assertEquals(2, items.size)
            val selected = JsonObject(source.toMutableMap().apply {
                put("item", JsonArray(items))
                put("variable", buildJsonArray {
                    for ((key, value) in mapOf("base_url" to "http://127.0.0.1:${sink.address.port}/api/v1",
                        "run_email" to "manual-fixture@gmail.com", "run_password" to "FixtureOnlyAb!")) {
                        add(buildJsonObject { put("key", key); put("value", value) })
                    }
                })
            })
            file.writeText(selected.toString())
            val builder = ProcessBuilder("cmd.exe", "/c", "npx.cmd", "--offline", "--yes", "newman@6.2.1", "run", file.path,
                "--reporters", "cli", "--reporter-cli-no-console", "--reporter-cli-no-banner",
                "--reporter-cli-no-failures", "--reporter-cli-no-assertions", "--timeout-request", "5000")
                .redirectErrorStream(true).redirectOutput(File("build/postman-missing-password.log"))
            builder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
            builder.environment()["no_proxy"] = "127.0.0.1,localhost"
            val process = builder.start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            assertTrue(finished, "Newman manual-reset guard test timed out")
            assertEquals(0, process.exitValue(), "Newman guard check failed; inspect bounded test diagnostics locally")
            assertEquals(0, received.get(), "Missing local password must not send either reset request")
        } finally { sink.stop(0); file.delete() }
    }
}
