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
    val db = Database(System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/smart_shopping",
        System.getenv("DB_USER") ?: "smart_shopping_app", required("DB_PASSWORD"))
    try {
        db.migrate()
        val policy = AuthPolicy(number("ACCESS_TTL_SECONDS",900), number("SESSION_TTL_SECONDS",2592000),
            number("AUTH_RATE_LIMIT",20).toInt(), number("AUTH_RATE_WINDOW_SECONDS",60),
            number("LOGIN_LOCK_THRESHOLD",10).toInt(), number("LOGIN_LOCK_SECONDS",900))
        embeddedServer(Netty, host = System.getenv("HTTP_HOST") ?: "127.0.0.1",
            port = number("HTTP_PORT",8080).toInt()) { authModule(AuthService(db, policy = policy)) }.start(wait = true)
    } finally { db.close() }
}

fun Application.authModule(service: AuthService) {
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
                "Request could not be completed.", retryable = failure.status == 429, field_errors = failure.fields), meta(call)))
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
                    service.register(text(body,"email"),text(body,"password"),text(body,"confirm_password"),optionalText(body,"device_name"))
                }
                call.respond(HttpStatusCode.Created,Envelope(result,meta(call)))
            }
            post("/auth/login") {
                val body = body(call,setOf("email","password"),setOf("device_name"))
                val result = withContext(Dispatchers.IO) {
                    service.rateLimit(call.request.local.remoteHost,"login")
                    service.login(text(body,"email"),text(body,"password"),optionalText(body,"device_name"))
                }
                call.respond(Envelope(result,meta(call)))
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
        }
    }
}

private fun meta(call: ApplicationCall) = Meta(call.attributes[requestIdKey])
private fun bearer(call: ApplicationCall): String {
    val header = call.request.headers[HttpHeaders.Authorization] ?: throw ApiFailure(401,"AUTH_REQUIRED")
    val pieces = header.split(' ')
    if (pieces.size != 2 || !pieces[0].equals("Bearer",true) || pieces[1].isBlank()) throw ApiFailure(401,"ACCESS_TOKEN_INVALID")
    return pieces[1]
}

private suspend fun body(call: ApplicationCall, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
    if (!call.request.contentType().match(ContentType.Application.Json)) throw ApiFailure(400,"MALFORMED_REQUEST")
    // Bounded read prevents a large credential request from allocating an unbounded body.
    val bytes = call.receiveChannel().readRemaining(8193).readByteArray()
    if (bytes.size > 8192) throw ApiFailure(400,"MALFORMED_REQUEST")
    val parsed = wireJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw ApiFailure(400,"MALFORMED_REQUEST")
    if ((parsed.keys - required - optional).isNotEmpty()) throw ApiFailure(400,"UNKNOWN_FIELD")
    if (!parsed.keys.containsAll(required)) throw ApiFailure(400,"MALFORMED_REQUEST")
    required.forEach { text(parsed,it) }
    optional.forEach { optionalText(parsed,it) }
    return parsed
}

private fun text(body: JsonObject, name: String): String {
    val value = body[name] as? JsonPrimitive ?: throw ApiFailure(400,"MALFORMED_REQUEST")
    if (!value.isString) throw ApiFailure(400,"MALFORMED_REQUEST")
    return value.content
}

private fun optionalText(body: JsonObject, name: String): String? = if (name in body) text(body,name) else null
