package com.toked.smartshopping.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap

/** Explicit test-only executable: loopback, isolated PostgreSQL, no real email or Google credentials. */
fun main() {
    val url = "jdbc:postgresql://127.0.0.1:55432/auth00_test"
    val db = Database(url,"auth00_test","")
    db.migrate()
    db.transaction { c ->
        execute(c, "INSERT INTO shopping_categories(id,name,status) VALUES ('fixture-food','Thực phẩm thử nghiệm','ACTIVE') ON CONFLICT DO NOTHING")
        execute(c, "INSERT INTO shopping_brands(id,name,status) VALUES ('fixture-brand','Thương hiệu thử nghiệm','ACTIVE') ON CONFLICT DO NOTHING")
    }
    val codes = ConcurrentHashMap<String,String>()
    val service = AuthService(db,policy = AuthPolicy(rateLimit = 500))
    val flows = AuthFlows(db,service,AuthMailer { email,code,_ -> codes[email] = code },
        GoogleVerifier { throw ApiFailure(401,"GOOGLE_TOKEN_INVALID") },ByteArray(32) { 9 })
    try {
        embeddedServer(Netty,host = "127.0.0.1",port = 18080) {
            authModule(service,flows)
            routing {
                get("/fixture/code") { call.respondText(codes[call.request.queryParameters["email"]].orEmpty()) }
                get("/fixture/health") { call.respondText("isolated-auth-fixture") }
            }
        }.start(wait = true)
    } finally { db.close() }
}
