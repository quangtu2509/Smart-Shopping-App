package com.toked.smartshopping.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.util.UUID

private val wireJson = Json { encodeDefaults = true; explicitNulls = true }
private val requestIdKey = io.ktor.util.AttributeKey<String>("request-id")

fun main() {
    fun required(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Set $name in the local environment before starting the backend")
    fun number(name: String, default: Long) = System.getenv(name)?.toLong() ?: default
    val challengeKey = try { java.util.Base64.getDecoder().decode(required("AUTH_CHALLENGE_KEY")) }
        catch (_: IllegalArgumentException) { error("AUTH_CHALLENGE_KEY must be Base64 with at least 32 decoded bytes") }
    require(challengeKey.size >= 32)
    val googleClientId = required("GOOGLE_WEB_CLIENT_ID")
    val mailer = SmtpAuthMailer(required("SMTP_USERNAME"), required("SMTP_PASSWORD"),
        System.getenv("SMTP_HOST") ?: "smtp.gmail.com", number("SMTP_PORT", 587).toInt())
    val db = Database(System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/smart_shopping",
        System.getenv("DB_USER") ?: "smart_shopping_app", required("DB_PASSWORD"))
    try {
        db.migrate()
        val policy = AuthPolicy(number("ACCESS_TTL_SECONDS",900), number("SESSION_TTL_SECONDS",2592000),
            number("AUTH_RATE_LIMIT",20).toInt(), number("AUTH_RATE_WINDOW_SECONDS",60),
            number("LOGIN_LOCK_THRESHOLD",10).toInt(), number("LOGIN_LOCK_SECONDS",900))
        val service = AuthService(db, policy = policy)
        val flows = AuthFlows(db, service, mailer, GoogleTokenVerifier(googleClientId), challengeKey)
        embeddedServer(Netty, host = System.getenv("HTTP_HOST") ?: "127.0.0.1",
            port = number("HTTP_PORT",8080).toInt()) { authModule(service, flows) }.start(wait = true)
    } finally { db.close() }
}

fun Application.authModule(service: AuthService, flows: AuthFlows? = null) {
    val profiles = service.shoppingProfiles()
    val plans = service.shoppingPlans()
    fun extended() = flows ?: throw ApiFailure(503, "AUTH_PROVIDER_UNCONFIGURED")
    install(ContentNegotiation) { json(wireJson) }
    intercept(ApplicationCallPipeline.Setup) {
        val id = "req_${UUID.randomUUID()}"
        call.attributes.put(requestIdKey,id)
        call.response.header("X-Request-ID",id)
        call.response.header(HttpHeaders.CacheControl,"no-store")
    }
    install(StatusPages) {
        exception<ApiFailure> { call, failure ->
            if (failure.status == 401 && call.request.path() !in setOf("/api/v1/auth/login", "/api/v1/auth/refresh"))
                call.response.header(HttpHeaders.WWWAuthenticate,"Bearer")
            failure.retryAfter?.let { call.response.header(HttpHeaders.RetryAfter,it.toString()) }
            call.respond(HttpStatusCode.fromValue(failure.status), ErrorEnvelope(ApiError(failure.code,
                "Request could not be completed.", details = failure.details, retryable = failure.status == 429, field_errors = failure.fields), meta(call)))
        }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest,
            ErrorEnvelope(ApiError("MALFORMED_REQUEST","Invalid JSON."),meta(call))) }
        exception<Throwable> { call, failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // No exception text, request body, headers or SQL values are written to logs.
            call.application.environment.log.error("Unhandled request failure; request_id={}", call.attributes[requestIdKey])
            call.respond(HttpStatusCode.InternalServerError,ErrorEnvelope(ApiError("INTERNAL_ERROR","Internal server error."),meta(call)))
        }
        status(HttpStatusCode.NotFound) { call, _ -> call.respond(HttpStatusCode.NotFound,
            ErrorEnvelope(ApiError("RESOURCE_NOT_FOUND","Resource not found."),meta(call))) }
    }
    routing {
        route("/api/v1") {
            post("/auth/register") {
                val body = body(call,setOf("email","password","confirm_password"),setOf("device_name"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost,"register")
                    extended().register(text(body,"email"),text(body,"password"),text(body,"confirm_password"),optionalText(body,"device_name"),call.request.local.remoteHost)
                }
                call.respond(HttpStatusCode.Accepted,Envelope(result,meta(call)))
            }
            post("/auth/login") {
                val body = body(call,setOf("email","password"),setOf("device_name"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost,"login")
                    if (flows == null) service.login(text(body,"email"),text(body,"password"),optionalText(body,"device_name"))
                    else flows.login(text(body,"email"),text(body,"password"),optionalText(body,"device_name"),call.request.local.remoteHost)
                }
                respondAuthStep(call, result)
            }
            post("/auth/email/verify") {
                val b = body(call, setOf("challenge_id", "code"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "verify-email")
                    extended().verifyEmail(text(b,"challenge_id"), text(b,"code"))
                }
                call.respond(Envelope(result, meta(call)))
            }
            post("/auth/email/resend") {
                val b = body(call, setOf("challenge_id"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "resend")
                    extended().resend(text(b,"challenge_id"), call.request.local.remoteHost)
                }
                call.respond(HttpStatusCode.Accepted, Envelope(result, meta(call)))
            }
            post("/auth/google/challenge") {
                body(call, emptySet())
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "google-challenge")
                    extended().googleChallenge()
                }
                call.respond(Envelope(result, meta(call)))
            }
            post("/auth/google") {
                val b = body(call, setOf("challenge_id", "id_token"), setOf("device_name"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "google")
                    extended().google(text(b,"challenge_id"),text(b,"id_token"),optionalText(b,"device_name"))
                }
                respondAuthStep(call, result)
            }
            post("/auth/google/link") {
                val b = body(call, setOf("challenge_id", "password"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "google-link")
                    extended().link(text(b,"challenge_id"),text(b,"password"))
                }
                call.respond(Envelope(result, meta(call)))
            }
            post("/auth/password/forgot") {
                val b = body(call, setOf("email"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "forgot")
                    extended().forgot(text(b,"email"),call.request.local.remoteHost)
                }
                call.respond(HttpStatusCode.Accepted, Envelope(result, meta(call)))
            }
            post("/auth/password/verify") {
                val b = body(call, setOf("challenge_id", "code"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "verify-reset")
                    extended().verifyReset(text(b,"challenge_id"),text(b,"code"))
                }
                call.respond(Envelope(result, meta(call)))
            }
            post("/auth/password/reset") {
                val b = body(call, setOf("reset_token", "password", "confirm_password"))
                withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost, "reset")
                    extended().reset(text(b,"reset_token"),text(b,"password"),text(b,"confirm_password"))
                }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/auth/refresh") {
                val body = body(call,setOf("refresh_token"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost,"refresh")
                    service.refresh(text(body,"refresh_token"))
                }
                call.respond(Envelope(result,meta(call)))
            }
            post("/auth/logout") {
                val body = body(call,setOf("refresh_token"))
                withContext(Dispatchers.IO) { service.logout(text(body,"refresh_token")) }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/auth/sessions") {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull()
                    ?: throw ApiFailure(422,"VALIDATION_ERROR",listOf(FieldError("limit","INVALID_INTEGER"))) } ?: 20
                val result = withContext(Dispatchers.IO) { service.sessions(bearer(call),limit,call.request.queryParameters["cursor"]) }
                call.respond(Envelope(result,meta(call)))
            }
            delete("/auth/sessions/{auth_session_id}") {
                withContext(Dispatchers.IO) { service.revoke(bearer(call),call.parameters["auth_session_id"]!!) }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/auth/logout-all") {
                body(call,emptySet())
                withContext(Dispatchers.IO) { service.logoutAll(bearer(call)) }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/me") {
                val result = withContext(Dispatchers.IO) { service.me(bearer(call)) }
                call.respond(Envelope(result,meta(call)))
            }
            get("/me/shopping-profile") {
                val result = withContext(Dispatchers.IO) { profiles.get(bearer(call)) }
                call.respond(Envelope(result, meta(call)))
            }
            patch("/me/shopping-profile") {
                val b = body(call, setOf("expected_version"), setOf("household_size", "default_period",
                    "preferred_category_ids", "avoided_category_ids", "preferred_brand_ids", "avoided_brand_ids",
                    "max_stores", "optimization_mode"), stringsOnly = false)
                val result = withContext(Dispatchers.IO) { profiles.patch(bearer(call), call.request.headers["Idempotency-Key"], b) }
                call.respond(Envelope(result, meta(call)))
            }
            post("/plans") {
                val access = bearer(call)
                val b = body(call, setOf("budget", "currency", "selections", "category_ids"), stringsOnly = false)
                val result = withContext(Dispatchers.IO) { plans.create(access, call.request.headers["Idempotency-Key"], b) }
                result.location?.let { call.response.header(HttpHeaders.Location, it) }
                call.respond(HttpStatusCode.Created, Envelope(result.data, meta(call)))
            }
            get("/plans") {
                val p = call.request.queryParameters
                val result = withContext(Dispatchers.IO) { plans.list(bearer(call), p["status"], queryLimit(call), p["cursor"]) }
                call.respond(Envelope(result, meta(call)))
            }
            get("/plans/{plan_id}") {
                val result = withContext(Dispatchers.IO) { plans.get(bearer(call), call.parameters["plan_id"]!!) }
                call.respond(Envelope(result, meta(call)))
            }
            patch("/plans/{plan_id}") {
                val access = bearer(call)
                val b = body(call, setOf("expected_version"), setOf("budget", "selections", "category_ids"), stringsOnly = false)
                val result = withContext(Dispatchers.IO) { plans.patch(access, call.parameters["plan_id"]!!, call.request.headers["Idempotency-Key"], b) }
                call.respond(Envelope(result.data, meta(call)))
            }
            get("/map/stores") {
                val p = call.request.queryParameters
                val result = withContext(Dispatchers.IO) { plans.stores(bearer(call), p["bbox"], p["q"], p["retailer_ids"], queryLimit(call), p["cursor"]) }
                call.respond(Envelope(result, meta(call)))
            }
            get("/stores/{store_id}") {
                val result = withContext(Dispatchers.IO) { plans.store(bearer(call), call.parameters["store_id"]!!) }
                call.respond(Envelope(result, meta(call)))
            }
            for (kind in listOf("categories", "brands", "retailers")) get("/$kind") {
                val parameters = call.request.queryParameters
                val limit = parameters["limit"]?.toIntOrNull() ?: if (parameters["limit"] == null) 20
                    else throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError("limit", "INVALID_INTEGER")))
                val result = withContext(Dispatchers.IO) {
                    profiles.references(bearer(call), kind, parameters["q"], parameters["parent_id"], limit, parameters["cursor"])
                }
                call.respond(Envelope(result, meta(call)))
            }
        }
    }
}

private suspend fun respondAuthStep(call: ApplicationCall, result: Any) {
    when (result) {
        is AuthResult -> call.respond(Envelope(result, meta(call)))
        is ChallengeDto -> call.respond(HttpStatusCode.Accepted, Envelope(result, meta(call)))
        else -> error("Unsupported authentication step")
    }
}

private fun meta(call: ApplicationCall) = Meta(call.attributes[requestIdKey])
private fun queryLimit(call: ApplicationCall): Int = call.request.queryParameters["limit"]?.let {
    it.toIntOrNull() ?: throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError("limit", "INVALID_INTEGER")))
} ?: 20
private fun bearer(call: ApplicationCall): String {
    val header = call.request.headers[HttpHeaders.Authorization] ?: throw ApiFailure(401,"AUTH_REQUIRED")
    val pieces = header.split(' ')
    if (pieces.size != 2 || !pieces[0].equals("Bearer",true) || pieces[1].isBlank()) throw ApiFailure(401,"ACCESS_TOKEN_INVALID")
    return pieces[1]
}

private suspend fun body(call: ApplicationCall, required: Set<String>, optional: Set<String> = emptySet(), stringsOnly: Boolean = true): JsonObject {
    if (!call.request.contentType().match(ContentType.Application.Json)) throw ApiFailure(400,"MALFORMED_REQUEST")
    // Bounded read prevents a large credential request from allocating an unbounded body.
    val bytes = call.receiveChannel().readRemaining(8193).readByteArray()
    if (bytes.size > 8192) throw ApiFailure(400,"MALFORMED_REQUEST")
    val parsed = wireJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw ApiFailure(400,"MALFORMED_REQUEST")
    if ((parsed.keys - required - optional).isNotEmpty()) throw ApiFailure(400,"UNKNOWN_FIELD")
    if (!parsed.keys.containsAll(required)) throw ApiFailure(400,"MALFORMED_REQUEST")
    if (stringsOnly) {
        required.forEach { text(parsed,it) }
        optional.forEach { optionalText(parsed,it) }
    }
    return parsed
}

private fun text(body: JsonObject, name: String): String {
    val value = body[name] as? JsonPrimitive ?: throw ApiFailure(400,"MALFORMED_REQUEST")
    if (!value.isString) throw ApiFailure(400,"MALFORMED_REQUEST")
    return value.content
}

private fun optionalText(body: JsonObject, name: String): String? = if (name in body) text(body,name) else null
