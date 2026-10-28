package com.toked.smartshopping.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class AuthIntegrationTest {
    private lateinit var db: Database
    private lateinit var service: AuthService
    private val clock = MutableClock()
    private val password = " Unicode password mật khẩu 2026! "

    @Before fun setup() {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:55432/auth00_test"))) { "Refusing to reset any non-isolated database" }
        db = Database(url,"auth00_test","")
        db.migrate()
        resetAuthTestData(db)
        clock.now = Instant.parse("2026-10-12T00:00:00Z")
        service = AuthService(db,clock = clock)
    }
    @After fun close() { if (::db.isInitialized) db.close() }

    @Test fun registrationNormalizesAndStoresOnlyHashes() {
        val auth = service.register("  Shopper@EXAMPLE.com ",password,password,"Android")
        assertEquals("shopper@example.com",auth.user.email)
        assertEquals(ProfileDto(),auth.profile)
        assertEquals(900L,auth.tokens.expires_in)
        assertEquals(auth.user,service.me(auth.tokens.access_token).user)
        failure(409,"EMAIL_ALREADY_EXISTS") { service.register("SHOPPER@example.com",password,password,null) }
        db.transaction { connection ->
            query(connection,"SELECT password_hash FROM app_users").use { statement -> statement.executeQuery().use { rows ->
                assertTrue(rows.next()); assertTrue(rows.getString(1).startsWith("\$argon2id\$")); assertNotEquals(password,rows.getString(1))
            } }
            query(connection,"SELECT access_hash,current_refresh_hash FROM auth_sessions").use { statement -> statement.executeQuery().use { rows ->
                assertTrue(rows.next()); assertEquals(64,rows.getString(1).length)
                assertNotEquals(auth.tokens.access_token,rows.getString(1)); assertNotEquals(auth.tokens.refresh_token,rows.getString(2))
            } }
        }
        failure(401,"INVALID_CREDENTIALS") { service.login("shopper@example.com",password.trim(),null) }
    }

    @Test fun invalidRegistrationAndProfileFailureLeaveNoPartialAccount() {
        failure(422,"VALIDATION_ERROR") { service.register("a@example.com","short","short",null) }
        failure(422,"VALIDATION_ERROR") { service.register("a@example.com",password,"different",null) }
        db.transaction { execute(it,"ALTER TABLE profiles ADD CONSTRAINT injected_failure CHECK (max_stores=2)") }
        try {
            assertFailsWith<java.sql.SQLException> { service.register("rollback@example.com",password,password,null) }
            db.transaction { connection -> query(connection,"SELECT count(*) FROM app_users").use { statement ->
                statement.executeQuery().use { rows -> rows.next(); assertEquals(0,rows.getInt(1)) }
            } }
        } finally { db.transaction { execute(it,"ALTER TABLE profiles DROP CONSTRAINT injected_failure") } }
    }

    @Test fun refreshRotatesAndReplayRevokesIncludingNewAccess() {
        val auth = registered()
        val next = service.refresh(auth.tokens.refresh_token)
        assertEquals(auth.tokens.refresh_expires_at,next.refresh_expires_at)
        failure(401,"ACCESS_TOKEN_INVALID") { service.me(auth.tokens.access_token) }
        assertEquals(auth.user,service.me(next.access_token).user)
        failure(401,"REFRESH_TOKEN_INVALID") { service.refresh(auth.tokens.refresh_token) }
        failure(401,"SESSION_REVOKED") { service.me(next.access_token) }
        failure(401,"REFRESH_TOKEN_INVALID") { service.refresh(next.refresh_token) }
    }

    @Test fun expirationNeverExtendsAbsoluteSessionLifetime() {
        val auth = registered()
        clock.now = clock.now.plusSeconds(900)
        failure(401,"ACCESS_TOKEN_EXPIRED") { service.me(auth.tokens.access_token) }
        val next = service.refresh(auth.tokens.refresh_token)
        assertEquals(auth.tokens.refresh_expires_at,next.refresh_expires_at)
        clock.now = Instant.parse(next.refresh_expires_at).minusSeconds(10)
        val last = service.refresh(next.refresh_token)
        assertEquals(10L,last.expires_in)
        clock.now = clock.now.plusSeconds(10)
        failure(401,"REFRESH_TOKEN_INVALID") { service.refresh(last.refresh_token) }
    }

    @Test fun ownershipPaginationAndRevocation() {
        val first = registered()
        val second = service.login("one@example.com",password,"second")
        val stranger = service.register("two@example.com",password,password,null)
        val page = service.sessions(first.tokens.access_token,1,null)
        assertEquals(1,page.items.size); assertNotNull(page.next_cursor)
        assertEquals(1,service.sessions(first.tokens.access_token,1,page.next_cursor).items.size)
        failure(400,"INVALID_CURSOR") { service.sessions(stranger.tokens.access_token,1,page.next_cursor) }
        failure(404,"RESOURCE_NOT_FOUND") { service.revoke(first.tokens.access_token,stranger.tokens.auth_session_id) }
        service.revoke(first.tokens.access_token,second.tokens.auth_session_id)
        failure(401,"SESSION_REVOKED") { service.me(second.tokens.access_token) }
        service.logoutAll(first.tokens.access_token)
        failure(401,"SESSION_REVOKED") { service.me(first.tokens.access_token) }
        assertEquals(stranger.user,service.me(stranger.tokens.access_token).user)
        assertEquals(first.user,service.login("one@example.com",password,null).user)
    }

    @Test fun logoutIsIdempotentAndOldRefreshCanOnlyRevokeOwnSession() {
        val first = registered()
        val second = service.login("one@example.com",password,null)
        val rotated = service.refresh(first.tokens.refresh_token)
        service.logout(first.tokens.refresh_token)
        service.logout(first.tokens.refresh_token)
        service.logout("unknown")
        failure(401,"SESSION_REVOKED") { service.me(rotated.access_token) }
        service.me(second.tokens.access_token)
    }

    @Test fun lockAndRateLimitDoNotExposeUnknownAccounts() {
        val original = registered()
        service = AuthService(db,clock = clock,policy = AuthPolicy(lockThreshold = 2,rateLimit = 2))
        repeat(2) { failure(401,"INVALID_CREDENTIALS") { service.login("one@example.com","wrong",null) } }
        failure(401,"INVALID_CREDENTIALS") { service.login("one@example.com","wrong",null) }
        failure(401,"INVALID_CREDENTIALS") { service.login("missing@example.com",password,null) }
        failure(403,"ACCOUNT_LOCKED") { service.login("one@example.com",password,null) }
        clock.now = clock.now.plusSeconds(901)
        service.login("one@example.com",password,null)
        failure(401,"SESSION_REVOKED") { service.me(original.tokens.access_token) }
        service.rateLimit("peer","login"); service.rateLimit("peer","login")
        val limited = failure(429,"RATE_LIMITED") { service.rateLimit("peer","login") }
        assertNotNull(limited.retryAfter)
        clock.now = clock.now.plusSeconds(60)
        service.rateLimit("peer","login")
        db.transaction { execute(it,"UPDATE app_users SET status='LOCKED'") }
        failure(403,"ACCOUNT_LOCKED") { service.login("one@example.com",password,null) }
    }

    @Test fun concurrentRefreshHasOneWinnerAndReplayRevokesIt() = runBlocking<Unit> {
        val first = registered()
        val outcomes = List(2) { async(Dispatchers.IO) { runCatching { service.refresh(first.tokens.refresh_token) } } }.awaitAll()
        assertEquals(1,outcomes.count { it.isSuccess })
        assertEquals(1,outcomes.count { it.exceptionOrNull() is ApiFailure })
        val winner = outcomes.first { it.isSuccess }.getOrThrow()
        failure(401,"SESSION_REVOKED") { service.me(winner.access_token) }
    }

    @Test fun concurrentRegistrationCreatesExactlyOneAccount() = runBlocking {
        val results = List(2) { async(Dispatchers.IO) { runCatching { registered() } } }.awaitAll()
        assertEquals(1,results.count { it.isSuccess })
        assertEquals("EMAIL_ALREADY_EXISTS",(results.first { it.isFailure }.exceptionOrNull() as ApiFailure).code)
    }

    @Test fun httpContractsRejectMalformedUnknownAndUnauthenticatedRequests() = testApplication {
        val codes = mutableMapOf<String,String>()
        val flows = AuthFlows(db,service,AuthMailer { e,c,_ -> codes[e]=c },GoogleVerifier { throw ApiFailure(401,"GOOGLE_TOKEN_INVALID") },ByteArray(32),clock)
        application { authModule(service,flows) }
        suspend fun post(path: String, body: String) = client.post("/api/v1$path") { contentType(ContentType.Application.Json); setBody(body) }
        val malformed = post("/auth/register","{")
        assertEquals(HttpStatusCode.BadRequest,malformed.status)
        val unknown = post("/auth/logout","""{"refresh_token":"unknown","owner_id":"other"}""")
        assertEquals("UNKNOWN_FIELD",json(unknown.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val absent = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized,absent.status)
        assertEquals("Bearer",absent.headers[HttpHeaders.WWWAuthenticate])
        val error = json(absent.bodyAsText())["error"]!!.jsonObject
        assertTrue(error["details"] is JsonObject)
        assertFalse("field_errors" in error)
        val oversized = post("/auth/register"," ".repeat(8193))
        assertEquals(HttpStatusCode.BadRequest,oversized.status)
        val register = post("/auth/register",buildJsonObject {
            put("email","http@gmail.com"); put("password",password); put("confirm_password",password)
        }.toString())
        assertEquals(HttpStatusCode.Accepted,register.status)
        assertEquals("no-store",register.headers[HttpHeaders.CacheControl])
        val pending = json(register.bodyAsText())["data"]!!.jsonObject
        assertFalse("tokens" in pending)
        val verified = post("/auth/email/verify",buildJsonObject {
            put("challenge_id",pending["challenge_id"]!!.jsonPrimitive.content); put("code",codes.getValue("http@gmail.com"))
        }.toString())
        assertEquals(HttpStatusCode.OK,verified.status)
        val data = json(verified.bodyAsText())["data"]!!.jsonObject
        val access = data["tokens"]!!.jsonObject["access_token"]!!.jsonPrimitive.content
        val me = client.get("/api/v1/me") { bearerAuth(access) }
        assertEquals(HttpStatusCode.OK,me.status)
        assertFalse(me.bodyAsText().contains(access))
        assertFalse(me.bodyAsText().contains("password_hash"))
        val all = client.post("/api/v1/auth/logout-all") { bearerAuth(access); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.NoContent,all.status); assertEquals("",all.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/me") { bearerAuth(access) }.status)
    }

    @Test fun httpRateLimitHasRetryAfterAndNoCredentials() = testApplication {
        registered()
        val limitedService = AuthService(db,clock = clock,policy = AuthPolicy(rateLimit = 1))
        application { authModule(limitedService) }
        val request = buildJsonObject { put("email","one@example.com"); put("password",password) }.toString()
        suspend fun login() = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.OK,login().status)
        val limited = login()
        assertEquals(HttpStatusCode.TooManyRequests,limited.status)
        assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)
        val payload = json(limited.bodyAsText())
        assertEquals("RATE_LIMITED",payload["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertFalse("data" in payload)
    }

    @Test fun httpTemporaryLockProvidesWaitTimeOnlyAfterValidCredentials() = testApplication {
        registered()
        val locking = AuthService(db, clock = clock, policy = AuthPolicy(lockThreshold = 1, lockSeconds = 90))
        application { authModule(locking) }
        suspend fun login(value: String) = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", "one@example.com"); put("password", value) }.toString())
        }
        val invalid = login("wrong-password")
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals(null, invalid.headers[HttpHeaders.RetryAfter])
        val locked = login(password)
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertTrue(locked.headers[HttpHeaders.RetryAfter]!!.toLong() in 90..91)
        assertEquals("ACCOUNT_LOCKED", json(locked.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertFalse("data" in json(locked.bodyAsText()))
    }

    private fun registered() = service.register("one@example.com",password,password,"test")
    private fun failure(status: Int, code: String, action: () -> Unit): ApiFailure {
        val failure = assertFailsWith<ApiFailure> { action() }
        assertEquals(status,failure.status); assertEquals(code,failure.code)
        return failure
    }
    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject
    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
