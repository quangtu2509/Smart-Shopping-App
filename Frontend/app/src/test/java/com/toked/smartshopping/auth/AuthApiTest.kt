package com.toked.smartshopping.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class AuthApiTest {
    private lateinit var server: MockWebServer
    @Before fun before() { server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    @After fun after() { server.shutdown() }
    private fun api() = AuthApi(server.url("/api/v1/").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)
    private val response = """{"data":{"user":{"id":"user","email":"shopper@example.com","status":"ACTIVE"},"profile":{"max_stores":3,"optimization_mode":"BALANCED"},"tokens":{"access_token":"test-access","refresh_token":"test-refresh","auth_session_id":"session","token_type":"Bearer","expires_in":900,"refresh_expires_at":"2026-11-12T00:00:00Z"}},"meta":{"request_id":"test"}}"""

    @Test fun debugAllowsOnlyExactLocalHttpHosts() {
        listOf("127.0.0.1", "localhost", "10.0.2.2").forEach { host ->
            AuthApi("http://$host:8080/api/v1/", allowLocalHttp = true)
        }
        listOf("example.com", "10.0.2.20", "10.0.2.2.example.com", "192.168.1.10").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                AuthApi("http://$host:8080/api/v1/", allowLocalHttp = true)
            }
        }
    }

    @Test fun localHttpIsRejectedWithoutDebugOptIn() {
        listOf("127.0.0.1", "localhost", "10.0.2.2").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { AuthApi("http://$host:8080/api/v1/") }
        }
        AuthApi("https://example.com/api/v1/")
    }

    @Test fun loginUses200AndPreservesPasswordWithoutConfirmation() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))
        assertEquals("user", api().login(" shopper@example.com ", " password with spaces ").account.userId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/login", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("shopper@example.com", body.getString("email"))
        assertEquals(" password with spaces ", body.getString("password"))
        assertFalse(body.has("confirm_password")); assertTrue(body.has("device_name"))
    }

    @Test fun refreshExpiryAllowsOnlyPostgresPrecisionRounding() {
        assertTrue(withinOriginalSessionExpiry("2026-11-12T00:00:00.123456700Z", "2026-11-12T00:00:00.123457Z"))
        assertTrue(withinOriginalSessionExpiry("2026-11-12T00:00:00.123456100Z", "2026-11-12T00:00:00.123456Z"))
        assertFalse(withinOriginalSessionExpiry("2026-11-12T00:00:00.123456Z", "2026-11-12T00:00:00.123457Z"))
        assertFalse(withinOriginalSessionExpiry("2026-11-12T00:00:00.123456700Z", "2026-11-12T00:00:00.124457Z"))
    }

    @Test fun refreshReadsTokensOnlyAndRejectsWrongSession() = runTest {
        val original = AuthSession(AccountProfile("user", "shopper@example.com", 3, "BALANCED"),
            "test-access", "test-refresh", "session", "2026-11-12T00:00:00Z")
        val tokens = JSONObject(response).getJSONObject("data").getJSONObject("tokens")
            .put("access_token", "new-access").put("refresh_token", "new-refresh")
        server.enqueue(MockResponse().setBody(JSONObject().put("data", tokens).toString()))
        val next = api().refresh(original)
        assertEquals("new-access", next.accessToken); assertEquals(original.account, next.account)
        val request = server.takeRequest()!!
        assertEquals("/api/v1/auth/refresh", request.path)
        assertEquals("test-refresh", JSONObject(request.body.readUtf8()).getString("refresh_token"))
        tokens.put("auth_session_id", "wrong-session")
        server.enqueue(MockResponse().setBody(JSONObject().put("data", tokens).toString()))
        try { api().refresh(original); fail() } catch (_: AuthFailure.InvalidResponse) { }
    }

    @Test fun logoutAndRevokeAccept204AndEncodeIdentifier() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        val client = api()
        client.logout("refresh"); client.revoke("access", "id/with?special"); client.logoutAll("access")
        assertEquals("/api/v1/auth/logout", server.takeRequest().path)
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/api/v1/auth/sessions/id%2Fwith%3Fspecial", revoke.path)
        assertEquals("Bearer access", revoke.getHeader("Authorization"))
        val all = server.takeRequest(); assertEquals("/api/v1/auth/logout-all", all.path); assertEquals("{}", all.body.readUtf8())
    }

    @Test fun sessionsDecodeCursorAndNeverSendTokensInUrl() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"items":[{"id":"s1","device_name":null,"created_at":"2026-10-16T00:00:00Z","last_used_at":"2026-10-16T00:00:00Z","expires_at":"2026-11-12T00:00:00Z","is_current":true}],"next_cursor":"next"}}"""))
        val page = api().sessions("secret", "a+b/c=")
        assertTrue(page.items.single().isCurrent); assertEquals("next", page.nextCursor)
        val request = server.takeRequest()
        assertEquals("a+b/c=", request.requestUrl!!.queryParameter("cursor"))
        assertEquals("20", request.requestUrl!!.queryParameter("limit"))
        assertFalse(request.path!!.contains("secret"))
    }

    @Test fun loginAndRefreshNeverRetryAfterLostResponse() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).setBody(response))
        try { api().login("a@example.com", "password"); fail() } catch (_: AuthFailure.Network) { }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).setBody(response))
        try { api().refresh(AuthSession(AccountProfile("u", "a@example.com", 3, "BALANCED"), "a", "r", "s", "2026-11-12T00:00:00Z")); fail() }
        catch (_: AuthFailure.Network) { }
        assertEquals(2, server.requestCount)
    }

    @Test fun registrationReturnsChallengeWithoutSavingCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"data":{"challenge_id":"pending","next_action":"VERIFY_EMAIL","expires_at":"2026-11-12T00:00:00Z","resend_after_seconds":60}}"""))
        try { api().register(" shopper@gmail.com ", " Long Unicode mật khẩu !", " Long Unicode mật khẩu !"); fail() }
        catch (step: AuthFailure.NextStep) { assertEquals("pending",step.challenge.id); assertEquals("VERIFY_EMAIL",step.challenge.nextAction) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST",request.method); assertEquals("/api/v1/auth/register",request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("shopper@gmail.com",body.getString("email"))
        assertEquals(" Long Unicode mật khẩu !",body.getString("password"))
    }
    @Test fun conflictValidationAndRetryAfterAreParsedWithoutServerMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"VALIDATION_ERROR","message":"must not show this raw message","details":{},"retryable":false,"field_errors":[{"field":"password","code":"INVALID_LENGTH"}]}}"""))
        try { api().register("a@example.com", "password", "password"); fail() }
        catch (failure: AuthFailure.Http) { assertEquals("INVALID_LENGTH", failure.fields["password"]); assertNull(failure.message) }
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody("""{"error":{"code":"RATE_LIMITED","details":{},"retryable":true}}"""))
        try { api().register("a@example.com", "password", "password"); fail() }
        catch (failure: AuthFailure.Http) { assertEquals(12, failure.retryAfter) }
    }
    @Test fun disconnectAfterPostDoesNotRetry() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(202).setBody(response))
        try { api().register("a@example.com", "password", "password"); fail() }
        catch (_: AuthFailure.Network) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun malformedSuccessCannotCreateSession() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        try { api().register("a@example.com", "password", "password"); fail() }
        catch (_: AuthFailure.InvalidResponse) { }
        server.enqueue(MockResponse().setResponseCode(202).setBody(response.replace("2026-11-12T00:00:00Z", "invalid-date")))
        try { api().register("a@example.com", "password", "password"); fail() }
        catch (_: AuthFailure.InvalidResponse) { }
    }
}
