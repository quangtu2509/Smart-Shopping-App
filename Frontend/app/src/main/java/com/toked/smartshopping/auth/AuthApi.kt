package com.toked.smartshopping.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import com.toked.smartshopping.shopping.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AccountProfile(val userId: String, val email: String, val maxStores: Int, val mode: String)
data class AuthSessionSummary(val id: String, val deviceName: String?, val createdAt: String,
    val lastUsedAt: String, val expiresAt: String, val isCurrent: Boolean)
data class AuthSessionPage(val items: List<AuthSessionSummary>, val nextCursor: String?)

class AuthSession(
    val account: AccountProfile,
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val refreshExpiresAt: String,
    val isNewUser: Boolean = false,
) {
    override fun toString() = "AuthSession(REDACTED)"
}

class AuthChallenge(val id: String, val nextAction: String, val expiresAt: String, val resendAfter: Int, val nonce: String? = null) {
    override fun toString() = "AuthChallenge(REDACTED)"
}

sealed class AuthFailure : Exception() {
    class NextStep(val challenge: AuthChallenge) : AuthFailure()
    class GoogleCancelled : AuthFailure()
    class Http(val status: Int, val code: String, val fields: Map<String, String>, val retryAfter: Int?) : AuthFailure()
    class Network : AuthFailure()
    class InvalidResponse : AuthFailure()
    class Unconfigured : AuthFailure()
    class SessionEnded : AuthFailure()
}

interface AuthGateway {
    suspend fun planReferences(accessToken: String, kind: String, cursor: String?): PlanReferencePage = throw AuthFailure.Unconfigured()
    suspend fun planStores(accessToken: String, retailerId: String?, query: String, cursor: String?): PlanStorePage = throw AuthFailure.Unconfigured()
    suspend fun planStore(accessToken: String, id: String): PlanStore = throw AuthFailure.Unconfigured()
    suspend fun plans(accessToken: String, cursor: String?): PlanPage = throw AuthFailure.Unconfigured()
    suspend fun plan(accessToken: String, id: String): ShoppingPlanView = throw AuthFailure.Unconfigured()
    suspend fun savePlan(accessToken: String, mutation: PlanMutation): ShoppingPlanView = throw AuthFailure.Unconfigured()
    suspend fun shoppingProfile(accessToken: String): ShoppingProfile = throw AuthFailure.Unconfigured()
    suspend fun saveShoppingProfile(accessToken: String, patch: ProfilePatch): ShoppingProfile = throw AuthFailure.Unconfigured()
    suspend fun references(accessToken: String, kind: String, cursor: String? = null): ReferencePage = throw AuthFailure.Unconfigured()
    suspend fun verifyEmail(id: String, code: String): AuthSession = throw AuthFailure.Unconfigured()
    suspend fun resend(id: String): AuthChallenge = throw AuthFailure.Unconfigured()
    suspend fun forgot(email: String): AuthChallenge = throw AuthFailure.Unconfigured()
    suspend fun verifyReset(id: String, code: String): String = throw AuthFailure.Unconfigured()
    suspend fun reset(token: String, password: String, confirm: String): Unit = throw AuthFailure.Unconfigured()
    suspend fun googleChallenge(): AuthChallenge = throw AuthFailure.Unconfigured()
    suspend fun google(id: String, token: String): AuthSession = throw AuthFailure.Unconfigured()
    suspend fun linkGoogle(id: String, password: String): AuthSession = throw AuthFailure.Unconfigured()
    suspend fun register(email: String, password: String, confirmation: String): AuthSession
    suspend fun me(accessToken: String): AccountProfile
    suspend fun login(email: String, password: String): AuthSession
    suspend fun refresh(session: AuthSession): AuthSession
    suspend fun logout(refreshToken: String)
    suspend fun sessions(accessToken: String, cursor: String? = null): AuthSessionPage
    suspend fun revoke(accessToken: String, sessionId: String)
    suspend fun logoutAll(accessToken: String)
}

class AuthApi(baseUrl: String, allowLocalHttp: Boolean = false) : AuthGateway {
    private val base = baseUrl.trimEnd('/').plus('/').toHttpUrlOrNull()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    init {
        require(base == null || base.isHttps ||
            (allowLocalHttp && base.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) { "Auth requires HTTPS outside debug local hosts" }
    }

    override suspend fun register(email: String, password: String, confirmation: String): AuthSession {
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("confirm_password", confirmation).toString()
        throw AuthFailure.NextStep(challenge("auth/register",JSONObject(body)))
    }

    override suspend fun login(email: String, password: String): AuthSession = authResult(request(
        "auth/login", method = "POST", body = JSONObject().put("email", email.trim()).put("password", password)
            .put("device_name", "Smart Shopping Android").toString(),
    ))

    private fun authResult(response: String): AuthSession {
        return try {
            val data = JSONObject(response).getJSONObject("data")
            if (data.has("challenge_id")) throw AuthFailure.NextStep(decodeChallenge(data))
            decodeTokens(data.getJSONObject("tokens"), AuthCodec.account(data), data.optBoolean("is_new_user",false))
        } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
        catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
        catch (_: java.time.format.DateTimeParseException) { throw AuthFailure.InvalidResponse() }
    }

    private fun decodeChallenge(data: JSONObject): AuthChallenge {
        val action = required(data,"next_action")
        require(action in setOf("VERIFY_EMAIL","VERIFY_RESET","GOOGLE","CONFIRM_PASSWORD"))
        return AuthChallenge(required(data,"challenge_id"),action,required(data,"expires_at").also { Instant.parse(it) },
            data.getInt("resend_after_seconds").also { require(it in 0..3600) }, data.optString("nonce").takeIf { !data.isNull("nonce") && it.isNotBlank() })
    }
    private suspend fun post(path: String, body: JSONObject, expected: Int = 200) = request(path,"POST",body.toString(),expected = expected)
    private suspend fun challenge(path: String, body: JSONObject, expected: Int = 202): AuthChallenge = try {
        decodeChallenge(JSONObject(post(path,body,expected)).getJSONObject("data"))
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
      catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
      catch (_: java.time.format.DateTimeParseException) { throw AuthFailure.InvalidResponse() }
    override suspend fun verifyEmail(id: String, code: String) = authResult(post("auth/email/verify",JSONObject().put("challenge_id",id).put("code",code)))
    override suspend fun resend(id: String) = challenge("auth/email/resend",JSONObject().put("challenge_id",id))
    override suspend fun forgot(email: String) = challenge("auth/password/forgot",JSONObject().put("email",email.trim()))
    override suspend fun googleChallenge() = challenge("auth/google/challenge",JSONObject(),200)
    override suspend fun google(id: String, token: String) = authResult(post("auth/google",JSONObject().put("challenge_id",id).put("id_token",token)))
    override suspend fun linkGoogle(id: String, password: String) = authResult(post("auth/google/link",JSONObject().put("challenge_id",id).put("password",password)))
    override suspend fun verifyReset(id: String, code: String): String = try {
        required(JSONObject(post("auth/password/verify",JSONObject().put("challenge_id",id).put("code",code))).getJSONObject("data"),"reset_token")
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
    override suspend fun reset(token: String, password: String, confirm: String) {
        post("auth/password/reset",JSONObject().put("reset_token",token).put("password",password).put("confirm_password",confirm),204)
    }

    override suspend fun refresh(session: AuthSession): AuthSession = try {
        val data = JSONObject(request("auth/refresh", method = "POST",
            body = JSONObject().put("refresh_token", session.refreshToken).toString())).getJSONObject("data")
        decodeTokens(data, session.account).also {
            require(it.sessionId == session.sessionId)
            require(withinOriginalSessionExpiry(session.refreshExpiresAt, it.refreshExpiresAt))
            require(it.refreshToken != session.refreshToken && it.accessToken != session.accessToken)
        }
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
    catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
    catch (_: java.time.format.DateTimeParseException) { throw AuthFailure.InvalidResponse() }

    private fun decodeTokens(tokens: JSONObject, account: AccountProfile, isNew: Boolean = false): AuthSession {
        require(tokens.getString("token_type") == "Bearer" && tokens.getLong("expires_in") > 0)
        return AuthSession(account, required(tokens, "access_token"), required(tokens, "refresh_token"),
            required(tokens, "auth_session_id"), required(tokens, "refresh_expires_at").also { Instant.parse(it) }, isNew)
    }

    override suspend fun logout(refreshToken: String) {
        request("auth/logout", method = "POST", body = JSONObject().put("refresh_token", refreshToken).toString(), expected = 204)
    }

    override suspend fun sessions(accessToken: String, cursor: String?): AuthSessionPage = try {
        val data = JSONObject(request("auth/sessions", accessToken = accessToken, cursor = cursor)).getJSONObject("data")
        val entries = data.getJSONArray("items")
        val items = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            AuthSessionSummary(required(item, "id"), if (item.isNull("device_name")) null else item.getString("device_name"),
                required(item, "created_at").also { Instant.parse(it) }, required(item, "last_used_at").also { Instant.parse(it) },
                required(item, "expires_at").also { Instant.parse(it) }, item.getBoolean("is_current"))
        }
        AuthSessionPage(items, if (data.isNull("next_cursor")) null else required(data, "next_cursor"))
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
    catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
    catch (_: java.time.format.DateTimeParseException) { throw AuthFailure.InvalidResponse() }

    override suspend fun revoke(accessToken: String, sessionId: String) {
        request("auth/sessions", method = "DELETE", accessToken = accessToken, segment = sessionId, expected = 204)
    }
    override suspend fun logoutAll(accessToken: String) {
        request("auth/logout-all", method = "POST", body = "{}", accessToken = accessToken, expected = 204)
    }

    override suspend fun me(accessToken: String): AccountProfile = try {
        AuthCodec.account(JSONObject(request("me", accessToken = accessToken)).getJSONObject("data"))
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
    catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }

    override suspend fun shoppingProfile(accessToken: String): ShoppingProfile = profileResponse(request("me/shopping-profile", accessToken = accessToken))
    override suspend fun saveShoppingProfile(accessToken: String, patch: ProfilePatch): ShoppingProfile = profileResponse(
        request("me/shopping-profile", "PATCH", patch.body, accessToken, idempotencyKey = patch.key))
    private fun profileResponse(response: String): ShoppingProfile = try {
        ShoppingProfile.decode(JSONObject(response).getJSONObject("data"))
    } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
      catch (_: IllegalArgumentException) { throw AuthFailure.InvalidResponse() }
    override suspend fun references(accessToken: String, kind: String, cursor: String?): ReferencePage {
        require(kind in setOf("categories", "brands"))
        return try {
            val data = JSONObject(request(kind, accessToken = accessToken, cursor = cursor)).getJSONObject("data")
            val items = data.getJSONArray("items")
            ReferencePage((0 until items.length()).map { index -> items.getJSONObject(index).let {
                ProfileReference(it.getString("id"), it.getString("name"), it.getString("status"))
            } }, if (data.isNull("next_cursor")) null else data.getString("next_cursor"))
        } catch (_: org.json.JSONException) { throw AuthFailure.InvalidResponse() }
    }

    override suspend fun planReferences(accessToken: String, kind: String, cursor: String?): PlanReferencePage {
        require(kind in setOf("retailers", "categories", "brands"))
        return PlanDecoders.references(request(kind, accessToken = accessToken, query = mapOf("limit" to "20", "cursor" to cursor)))
    }
    override suspend fun planStores(accessToken: String, retailerId: String?, query: String, cursor: String?): PlanStorePage =
        PlanDecoders.stores(request("map/stores", accessToken = accessToken, query = mapOf("bbox" to "-180,-90,180,90",
            "retailer_ids" to retailerId, "q" to query.takeIf { it.isNotBlank() }, "cursor" to cursor, "limit" to "20")))
    override suspend fun planStore(accessToken: String, id: String): PlanStore =
        PlanDecoders.store(request("stores", accessToken = accessToken, segment = id))
    override suspend fun plans(accessToken: String, cursor: String?): PlanPage =
        PlanDecoders.page(request("plans", accessToken = accessToken, query = mapOf("limit" to "20", "cursor" to cursor)))
    override suspend fun plan(accessToken: String, id: String): ShoppingPlanView =
        PlanDecoders.view(request("plans", accessToken = accessToken, segment = id))
    override suspend fun savePlan(accessToken: String, mutation: PlanMutation): ShoppingPlanView = PlanDecoders.view(
        request("plans", if (mutation.planId == null) "POST" else "PATCH", mutation.body, accessToken,
            expected = if (mutation.planId == null) 201 else 200, segment = mutation.planId, idempotencyKey = mutation.key))

    private suspend fun request(path: String, method: String = "GET", body: String? = null,
        accessToken: String? = null, expected: Int = 200, cursor: String? = null, segment: String? = null,
        idempotencyKey: String? = null, query: Map<String, String?> = emptyMap(), segments: List<String> = emptyList()): String {
        val url = (base?.resolve(path) ?: throw AuthFailure.Unconfigured()).newBuilder().apply {
            if (segment != null) addPathSegment(segment)
            segments.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
            if (query.isEmpty() && path in setOf("auth/sessions", "categories", "brands") && method == "GET") {
                addQueryParameter("limit", "20")
                if (cursor != null) addQueryParameter("cursor", cursor)
            }
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure.Network())
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val bytes = response.body?.byteStream()?.let(::readBytesBounded) ?: byteArrayOf()
                            val text = bytes.toString(Charsets.UTF_8)
                            if (response.code != expected && !(response.code == 202 && path in setOf("auth/login","auth/google"))) throw httpFailure(response, text)
                            if (continuation.isActive) continuation.resume(text)
                        } catch (failure: AuthFailure) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } catch (_: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(AuthFailure.Network())
                        }
                    }
                }
            })
        }
    }

    private fun httpFailure(response: Response, body: String): AuthFailure.Http {
        val error = try { JSONObject(body).optJSONObject("error") } catch (_: org.json.JSONException) { null }
        val fields = mutableMapOf<String, String>()
        error?.optJSONArray("field_errors")?.let { entries ->
            for (index in 0 until entries.length()) entries.optJSONObject(index)?.let {
                fields[it.optString("field")] = it.optString("code")
            }
        }
        return AuthFailure.Http(response.code, error?.optString("code").orEmpty(), fields,
            response.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600))
    }
    // Auth owns this I/O policy; never log the response body (it contains credentials).
    private fun readBytesBounded(stream: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = stream.read(buffer)
            if (size == -1) break
            if (output.size() + size > 65536) throw AuthFailure.InvalidResponse()
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}

private fun required(json: JSONObject, key: String) = json.getString(key).also { require(it.isNotEmpty()) }

internal fun withinOriginalSessionExpiry(original: String, rotated: String): Boolean {
    val originalInstant = Instant.parse(original)
    // PostgreSQL persists microseconds; JDBC rounds an initial JVM nanosecond timestamp.
    val persistedPrecision = originalInstant.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    return !Instant.parse(rotated).isAfter(maxOf(originalInstant, persistedPrecision))
}

internal object AuthCodec {
    fun account(data: JSONObject): AccountProfile {
        val user = data.getJSONObject("user")
        val profile = data.getJSONObject("profile")
        require(user.getString("status") == "ACTIVE")
        val maxStores = profile.getInt("max_stores")
        val mode = profile.getString("optimization_mode")
        require(maxStores in 1..3 && mode in setOf("CHEAPEST", "CONVENIENT", "BALANCED"))
        return AccountProfile(required(user, "id"), required(user, "email"), maxStores, mode)
    }
    fun encode(session: AuthSession): ByteArray = JSONObject()
        .put("user_id", session.account.userId).put("email", session.account.email)
        .put("max_stores", session.account.maxStores).put("mode", session.account.mode)
        .put("access_token", session.accessToken).put("refresh_token", session.refreshToken)
        .put("session_id", session.sessionId).put("refresh_expires_at", session.refreshExpiresAt)
        .toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): AuthSession {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val account = AccountProfile(required(json, "user_id"), required(json, "email"), json.getInt("max_stores"), required(json, "mode"))
        return AuthSession(account, required(json, "access_token"), required(json, "refresh_token"),
            required(json, "session_id"), required(json, "refresh_expires_at"))
    }
}
