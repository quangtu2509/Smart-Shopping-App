package com.toked.smartshopping.backend

import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

class AuthService(
    private val db: Database,
    private val security: Security = Security(),
    private val clock: Clock = Clock.systemUTC(),
    private val policy: AuthPolicy = AuthPolicy(),
) {
    private val dummyHash = security.passwordHash("Dummy password for timing only")
    private data class Account(val id: UUID, val email: String, val hash: String, val locked: Boolean)
    private data class Session(val id: UUID, val owner: UUID, val accessExpires: Instant,
        val refreshHash: String, val expires: Instant, val revoked: Boolean)
    private data class Principal(val user: Account, val session: Session)
    private data class Attempt<T>(val value: T? = null, val failure: ApiFailure? = null) {
        fun result(): T { failure?.let { throw it }; return checkNotNull(value) }
    }

    fun rateLimit(peer: String, route: String) {
        val now = clock.instant()
        val key = security.digest("$route:$peer")
        val remaining = db.transaction { connection ->
            query(connection, """
                INSERT INTO auth_rate_limits(bucket_hash,window_start,attempts) VALUES (?, ?, 1)
                ON CONFLICT(bucket_hash) DO UPDATE SET
                  attempts = CASE WHEN auth_rate_limits.window_start <= ? THEN 1 ELSE auth_rate_limits.attempts + 1 END,
                  window_start = CASE WHEN auth_rate_limits.window_start <= ? THEN EXCLUDED.window_start ELSE auth_rate_limits.window_start END
                RETURNING attempts,window_start
            """.trimIndent(), key, now, now.minusSeconds(policy.rateWindowSeconds), now.minusSeconds(policy.rateWindowSeconds)).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    if (rows.getInt("attempts") > policy.rateLimit)
                        maxOf(1L, rows.instant("window_start").plusSeconds(policy.rateWindowSeconds).epochSecond - now.epochSecond)
                    else null
                }
            }
        }
        if (remaining != null) throw ApiFailure(429, "RATE_LIMITED", retryAfter = remaining)
    }

    fun register(email: String, password: String, confirm: String, device: String?): AuthResult {
        val normalized = validate(email, password, device, registration = true)
        if (password != confirm) invalid("confirm_password", "PASSWORD_MISMATCH")
        val hash = security.passwordHash(password)
        try {
            return db.transaction { connection ->
                val id = UUID.randomUUID()
                execute(connection, "INSERT INTO app_users(id,email,password_hash,created_at) VALUES (?,?,?,?)", id, normalized, hash, clock.instant())
                execute(connection, "INSERT INTO profiles(user_id) VALUES (?)", id)
                AuthResult(UserDto(id.toString(), normalized, "ACTIVE"), ProfileDto(), createSession(connection, id, device))
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505" && failure.message?.contains("app_users_email_key") == true)
                throw ApiFailure(409, "EMAIL_ALREADY_EXISTS")
            throw failure
        }
    }

    fun login(email: String, password: String, device: String?): AuthResult {
        val normalized = validate(email, password, device, registration = false)
        return db.transaction { connection ->
            val account = query(connection, "SELECT * FROM app_users WHERE email=? FOR UPDATE", normalized).use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) account(rows) else null }
            }
            val matches = security.verify(password, account?.hash ?: dummyHash)
            if (account == null || !matches) {
                if (account != null) execute(connection, """
                    UPDATE app_users SET failed_logins=failed_logins+1,
                    locked_until=CASE WHEN failed_logins+1 >= ? THEN ? ELSE locked_until END WHERE id=?
                """.trimIndent(), policy.lockThreshold, clock.instant().plusSeconds(policy.lockSeconds), account.id)
                Attempt<AuthResult>(failure = ApiFailure(401, "INVALID_CREDENTIALS"))
            } else if (account.locked) {
                Attempt(failure = ApiFailure(403, "ACCOUNT_LOCKED"))
            } else {
                execute(connection, "UPDATE app_users SET failed_logins=0,locked_until=NULL WHERE id=?", account.id)
                Attempt(AuthResult(userDto(account), profile(connection, account.id), createSession(connection, account.id, device)))
            }
        }.result()
    }

    fun refresh(token: String): AuthTokens = db.transaction { connection ->
        val found = sessionForRefresh(connection, security.digest(token))
        if (found == null || found.second.locked || found.first.revoked || found.first.expires <= clock.instant()) {
            Attempt<AuthTokens>(failure = ApiFailure(401, "REFRESH_TOKEN_INVALID"))
        } else {
            val (session, _) = found
            if (session.refreshHash != security.digest(token)) {
                execute(connection, "UPDATE auth_sessions SET revoked_at=? WHERE id=?", clock.instant(), session.id)
                Attempt(failure = ApiFailure(401, "REFRESH_TOKEN_INVALID"))
            } else {
                val tokens = newTokens(session.id, session.expires)
                execute(connection, "UPDATE refresh_tokens SET used_at=? WHERE token_hash=?", clock.instant(), session.refreshHash)
                execute(connection, "UPDATE auth_sessions SET access_hash=?,access_expires_at=?,current_refresh_hash=?,last_used_at=? WHERE id=?",
                    security.digest(tokens.access_token), clock.instant().plusSeconds(tokens.expires_in), security.digest(tokens.refresh_token), clock.instant(), session.id)
                execute(connection, "INSERT INTO refresh_tokens(token_hash,session_id) VALUES (?,?)", security.digest(tokens.refresh_token), session.id)
                Attempt(tokens)
            }
        }
    }.result()

    fun logout(token: String) { db.transaction { connection ->
        val found = sessionForRefresh(connection, security.digest(token))
        if (found != null) execute(connection, "UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=?", clock.instant(), found.first.id)
    } }

    fun me(access: String): MeDto = db.transaction { connection ->
        val principal = authenticate(connection, access)
        MeDto(userDto(principal.user), profile(connection, principal.user.id))
    }

    fun sessions(access: String, limit: Int, cursor: String?): Page<AuthSessionDto> = db.transaction { connection ->
        val principal = authenticate(connection, access)
        if (limit !in 1..100) invalid("limit", "OUT_OF_RANGE")
        val after = cursor?.let { decodeCursor(it, principal.user.id) }
        val sql = """
            SELECT * FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>?
            ${if (after == null) "" else "AND (created_at,id) > (?,?)"} ORDER BY created_at,id LIMIT ?
        """.trimIndent()
        val values = mutableListOf<Any?>(principal.user.id, clock.instant())
        if (after != null) { values += after.first; values += after.second }
        values += limit + 1
        val result = query(connection, sql, *values.toTypedArray()).use { statement ->
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(AuthSessionDto(rows.uuid("id").toString(), rows.instant("created_at").toString(),
                    rows.instant("last_used_at").toString(), rows.instant("expires_at").toString(),
                    rows.uuid("id") == principal.session.id, rows.getString("device_name")))
            } }
        }
        val page = result.take(limit)
        val next = if (result.size > limit) page.last().let {
            Base64.getUrlEncoder().withoutPadding().encodeToString("${principal.user.id}|${it.created_at}|${it.id}".toByteArray())
        } else null
        Page(page, next)
    }

    fun revoke(access: String, sessionId: String) { db.transaction { connection ->
        val principal = authenticate(connection, access)
        val id = try { UUID.fromString(sessionId) } catch (_: IllegalArgumentException) { throw ApiFailure(404, "RESOURCE_NOT_FOUND") }
        val count = execute(connection, "UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=? AND user_id=?", clock.instant(), id, principal.user.id)
        if (count == 0) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
    } }

    fun logoutAll(access: String) { db.transaction { connection ->
        val principal = authenticate(connection, access)
        execute(connection, "UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?", clock.instant(), principal.user.id)
    } }

    private fun validate(email: String, password: String, device: String?, registration: Boolean): String {
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (normalized.length > 254 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalized)) invalid("email", "INVALID_EMAIL")
        val length = password.codePointCount(0, password.length)
        if (length > 128 || (registration && length < 15)) invalid("password", "INVALID_LENGTH")
        if (device != null && device.length > 100) invalid("device_name", "TOO_LONG")
        return normalized
    }

    private fun invalid(field: String, code: String): Nothing = throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError(field, code)))

    private fun account(rows: java.sql.ResultSet): Account = Account(rows.uuid("id"), rows.getString("email"),
        rows.getString("password_hash"), rows.getString("status") == "LOCKED" ||
            (rows.getTimestamp("locked_until")?.toInstant()?.isAfter(clock.instant()) == true))

    private fun userDto(account: Account) = UserDto(account.id.toString(), account.email, if (account.locked) "LOCKED" else "ACTIVE")

    private fun profile(connection: Connection, owner: UUID): ProfileDto = query(connection, "SELECT * FROM profiles WHERE user_id=?", owner).use { statement ->
        statement.executeQuery().use { rows ->
            check(rows.next())
            fun strings(name: String): List<String> = (rows.getArray(name).array as Array<*>).map { it.toString() }
            ProfileDto(rows.getInt("version"), rows.getObject("household_size") as Int?, rows.getString("default_period"),
                strings("preferred_category_ids"), strings("avoided_category_ids"), strings("preferred_brand_ids"), strings("avoided_brand_ids"),
                rows.getInt("max_stores"), rows.getString("optimization_mode"))
        }
    }

    private fun newTokens(id: UUID, expires: Instant) = AuthTokens(security.token(), expires_in =
        minOf(policy.accessSeconds, expires.epochSecond - clock.instant().epochSecond),
        refresh_token = security.token(), refresh_expires_at = expires.toString(), auth_session_id = id.toString())

    private fun createSession(connection: Connection, owner: UUID, device: String?): AuthTokens {
        val id = UUID.randomUUID()
        val now = clock.instant()
        val expires = now.plusSeconds(policy.sessionSeconds)
        val tokens = newTokens(id, expires)
        execute(connection, """
            INSERT INTO auth_sessions(id,user_id,access_hash,access_expires_at,current_refresh_hash,created_at,last_used_at,expires_at,device_name)
            VALUES (?,?,?,?,?,?,?,?,?)
        """.trimIndent(), id, owner, security.digest(tokens.access_token), now.plusSeconds(tokens.expires_in), security.digest(tokens.refresh_token), now, now, expires, device)
        execute(connection, "INSERT INTO refresh_tokens(token_hash,session_id) VALUES (?,?)", security.digest(tokens.refresh_token), id)
        return tokens
    }

    private fun lockedSession(connection: Connection, sessionId: UUID, owner: UUID): Pair<Session, Account> {
        // Lock user before session in every flow; this also serializes login/logout-all/refresh.
        val user = query(connection, "SELECT * FROM app_users WHERE id=? FOR UPDATE", owner).use { statement ->
            statement.executeQuery().use { rows -> check(rows.next()); account(rows) }
        }
        val session = query(connection, "SELECT * FROM auth_sessions WHERE id=? FOR UPDATE", sessionId).use { statement ->
            statement.executeQuery().use { rows -> check(rows.next()); Session(rows.uuid("id"), rows.uuid("user_id"),
                rows.instant("access_expires_at"), rows.getString("current_refresh_hash"), rows.instant("expires_at"), rows.getTimestamp("revoked_at") != null) }
        }
        return session to user
    }

    private fun sessionForRefresh(connection: Connection, hash: String): Pair<Session, Account>? {
        val identity = query(connection, "SELECT s.id,s.user_id FROM refresh_tokens t JOIN auth_sessions s ON s.id=t.session_id WHERE t.token_hash=?", hash).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.uuid("id") to rows.uuid("user_id") else null }
        } ?: return null
        return lockedSession(connection, identity.first, identity.second)
    }

    private fun authenticate(connection: Connection, access: String): Principal {
        val hash = security.digest(access)
        val identity = query(connection, "SELECT id,user_id FROM auth_sessions WHERE access_hash=?", hash).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.uuid("id") to rows.uuid("user_id") else null }
        } ?: throw ApiFailure(401, "ACCESS_TOKEN_INVALID")
        val (session, user) = lockedSession(connection, identity.first, identity.second)
        // Recheck after locks: a concurrent refresh might have replaced the token.
        val stillCurrent = query(connection, "SELECT 1 FROM auth_sessions WHERE id=? AND access_hash=?", session.id, hash).use { statement ->
            statement.executeQuery().use { it.next() }
        }
        if (!stillCurrent) throw ApiFailure(401, "ACCESS_TOKEN_INVALID")
        if (session.revoked || user.locked) throw ApiFailure(401, "SESSION_REVOKED")
        if (session.expires <= clock.instant() || session.accessExpires <= clock.instant()) throw ApiFailure(401, "ACCESS_TOKEN_EXPIRED")
        execute(connection, "UPDATE auth_sessions SET last_used_at=? WHERE id=?", clock.instant(), session.id)
        return Principal(user, session)
    }

    private fun decodeCursor(cursor: String, owner: UUID): Pair<Instant, UUID> {
        try {
            val pieces = String(Base64.getUrlDecoder().decode(cursor)).split('|')
            if (pieces.size != 3 || pieces[0] != owner.toString()) throw IllegalArgumentException()
            return Instant.parse(pieces[1]) to UUID.fromString(pieces[2])
        } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
        catch (_: java.time.format.DateTimeParseException) { throw ApiFailure(400, "INVALID_CURSOR") }
    }
}
