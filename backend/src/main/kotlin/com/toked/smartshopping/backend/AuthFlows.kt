package com.toked.smartshopping.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Provider calls happen outside database transactions. All credential steps are single-use. */
class AuthFlows(private val db: Database, private val auth: AuthService, private val mailer: AuthMailer,
    private val google: GoogleVerifier, key: ByteArray, private val clock: Clock = Clock.systemUTC()) {
    private val hmacKey = key.copyOf().also { require(it.size >= 32) { "AUTH_CHALLENGE_KEY must contain at least 32 decoded bytes" } }
    private val security = Security()
    private val random = SecureRandom()
    private data class Challenge(val id: String, val purpose: String, val email: String, val user: UUID?,
        val hash: String, val password: String?, val subject: String?, val device: String?, val expires: Instant,
        val sent: Instant, val attempts: Int, val consumed: Boolean)

    fun register(email: String, password: String, confirm: String, device: String?, peer: String): ChallengeDto {
        val normalized = email(email)
        if (normalized.substringAfter('@') != "gmail.com") invalid("email", "GMAIL_REQUIRED")
        password(password, confirm)
        device(device)
        val hashed = security.passwordHash(password)
        val code = code()
        val dto = db.transaction { c ->
            emailLock(c, normalized)
            if (user(c, normalized) != null) throw ApiFailure(409, "EMAIL_ALREADY_EXISTS")
            mailQuota(c, normalized, peer)
            insert(c, "REGISTER", normalized, secret = code, password = hashed, device = device)
        }
        deliver(normalized, code, "REGISTER")
        return dto
    }

    /** Returns either a completed AuthResult or a ChallengeDto; the route supplies its HTTP status. */
    fun login(email: String, password: String, device: String?, peer: String): Any {
        try { return auth.login(email, password, device) }
        catch (failure: ApiFailure) { if (failure.code != "EMAIL_VERIFICATION_REQUIRED") throw failure }
        // The password check already accepted this legacy address; do not reapply registration syntax.
        val normalized = email.trim().lowercase(Locale.ROOT)
        val code = code()
        val dto = db.transaction { c ->
            emailLock(c, normalized)
            val id = user(c, normalized) ?: throw ApiFailure(401, "INVALID_CREDENTIALS")
            mailQuota(c, normalized, peer)
            insert(c, "LOGIN", normalized, id, code, device = device)
        }
        deliver(normalized, code, "LOGIN")
        return dto
    }

    fun verifyEmail(id: String, code: String): AuthResult = consume(id, setOf("REGISTER", "LOGIN"), code) { c, challenge ->
        val fresh = challenge.purpose == "REGISTER"
        val owner = if (fresh) {
            if (user(c, challenge.email) != null) throw ApiFailure(409, "EMAIL_ALREADY_EXISTS")
            val owner = UUID.randomUUID()
            execute(c, "INSERT INTO app_users(id,email,password_hash,created_at,email_verified_at) VALUES (?,?,?,?,?)",
                owner, challenge.email, challenge.password, clock.instant(), clock.instant())
            execute(c, "INSERT INTO profiles(user_id) VALUES (?)", owner)
            owner
        } else checkNotNull(challenge.user)
        // Lock order: email advisory lock, challenge, user, then sessions.
        lockUser(c, owner)
        execute(c, "UPDATE app_users SET email_verified_at=COALESCE(email_verified_at,?) WHERE id=?", clock.instant(), owner)
        auth.complete(c, owner, challenge.device, fresh)
    }

    fun resend(id: String, peer: String): ChallengeDto {
        var code = code()
        val challenge = db.transaction { c ->
            val row = challenge(c, id)
            usable(row, setOf("REGISTER", "LOGIN", "RESET"))
            val seconds = row.sent.plusSeconds(60).epochSecond - clock.instant().epochSecond
            if (seconds > 0) throw ApiFailure(429, "RATE_LIMITED", retryAfter = seconds)
            mailQuota(c, row.email, peer)
            while (digest(id,code) == row.hash) code = code()
            // Attempts and the original expiry are deliberately retained across resends.
            execute(c, "UPDATE auth_challenges SET secret_hash=?,sent_at=? WHERE id=?", digest(id, code), clock.instant(), id)
            row
        }
        if (challenge.purpose != "RESET" || challenge.user != null) deliver(challenge.email, code, challenge.purpose, quiet = challenge.purpose == "RESET")
        return dto(challenge.id, challenge.purpose, challenge.expires)
    }

    fun forgot(email: String, peer: String): ChallengeDto {
        val normalized = accountEmail(email)
        val code = code()
        var recipient: UUID? = null
        val result = db.transaction { c ->
            emailLock(c, normalized)
            mailQuota(c, normalized, peer)
            recipient = query(c, "SELECT id FROM app_users WHERE email=? AND password_hash IS NOT NULL", normalized).use { q ->
                q.executeQuery().use { r -> if (r.next()) r.uuid("id") else null }
            }
            insert(c, "RESET", normalized, recipient, code)
        }
        if (recipient != null) deliver(normalized, code, "RESET", quiet = true)
        return result
    }

    fun verifyReset(id: String, code: String): ResetGrant = consume(id, setOf("RESET"), code) { c, row ->
        val owner = row.user ?: throw ApiFailure(400, "CHALLENGE_INVALID")
        lockUser(c, owner, checkTemporaryLock = false)
        val token = security.token()
        val grantId = security.digest(token)
        insert(c, "RESET_GRANT", row.email, owner, token, id = grantId)
        ResetGrant(token)
    }

    fun reset(token: String, password: String, confirm: String) {
        password(password, confirm)
        val hash = security.passwordHash(password)
        consume(security.digest(token), setOf("RESET_GRANT"), token) { c, row ->
            val owner = checkNotNull(row.user)
            lockUser(c, owner, checkTemporaryLock = false)
            // A Google-only account cannot acquire a local password through recovery.
            if (execute(c, "UPDATE app_users SET password_hash=?,email_verified_at=COALESCE(email_verified_at,?),failed_logins=0,locked_until=NULL WHERE id=? AND password_hash IS NOT NULL",
                    hash, clock.instant(), owner) != 1) throw ApiFailure(400, "CHALLENGE_INVALID")
            execute(c, "UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?", clock.instant(), owner)
            execute(c, "UPDATE auth_challenges SET consumed_at=COALESCE(consumed_at,?) WHERE user_id=? OR email=?", clock.instant(), owner, row.email)
            Unit
        }
    }

    fun googleChallenge(): ChallengeDto {
        val nonce = security.token()
        return db.transaction { c -> insert(c, "GOOGLE", secret = nonce) }.let {
            ChallengeDto(it.challenge_id, "GOOGLE", it.expires_at, 0, nonce)
        }
    }

    fun google(id: String, token: String, device: String?): Any {
        device(device)
        val identity = google.verify(token) // Network / signature validation happens before acquiring database locks.
        val normalized = email(identity.email)
        if (!identity.verified || normalized.substringAfter('@') != "gmail.com" || identity.subject.isBlank() || identity.nonce.isBlank())
            throw ApiFailure(401, "GOOGLE_TOKEN_INVALID")
        return consume(id, setOf("GOOGLE"), identity.nonce) { c, _ ->
            emailLock(c, normalized)
            val linked = query(c, "SELECT user_id FROM auth_identities WHERE provider='GOOGLE' AND subject=?", identity.subject).use { q ->
                q.executeQuery().use { r -> if (r.next()) r.uuid("user_id") else null }
            }
            if (linked != null) return@consume auth.complete(c, linked, device)
            val existing = user(c, normalized)
            if (existing != null) {
                lockUser(c, existing)
                return@consume insert(c, "LINK", normalized, existing, subject = identity.subject, device = device)
            }
            val owner = UUID.randomUUID()
            execute(c, "INSERT INTO app_users(id,email,created_at,email_verified_at) VALUES (?,?,?,?)", owner, normalized, clock.instant(), clock.instant())
            execute(c, "INSERT INTO profiles(user_id) VALUES (?)", owner)
            execute(c, "INSERT INTO auth_identities(provider,subject,user_id) VALUES ('GOOGLE',?,?)", identity.subject, owner)
            auth.complete(c, owner, device, true)
        }
    }

    fun link(id: String, password: String): AuthResult {
        if (password.codePointCount(0, password.length) !in 1..128) throw ApiFailure(401, "INVALID_CREDENTIALS")
        var badPassword = false
        val result = db.transaction { c ->
            val row = challenge(c, id)
            usable(row, setOf("LINK"))
            val owner = checkNotNull(row.user)
            lockUser(c, owner)
            val hash = query(c, "SELECT password_hash FROM app_users WHERE id=?", owner).use { q -> q.executeQuery().use { r -> r.next(); r.getString(1) } }
            if (hash == null || !security.verify(password, hash)) {
                execute(c, "UPDATE auth_challenges SET attempts=attempts+1 WHERE id=?", id)
                auth.failedPassword(c,owner)
                badPassword = true
                null
            } else {
                val conflict = query(c, "SELECT 1 FROM auth_identities WHERE provider='GOOGLE' AND (subject=? OR user_id=?)", row.subject, owner).use { q -> q.executeQuery().use { it.next() } }
                if (conflict) throw ApiFailure(409, "GOOGLE_ALREADY_LINKED")
                execute(c, "INSERT INTO auth_identities(provider,subject,user_id) VALUES ('GOOGLE',?,?)", row.subject, owner)
                execute(c, "UPDATE app_users SET email_verified_at=COALESCE(email_verified_at,?) WHERE id=?", clock.instant(), owner)
                execute(c, "UPDATE auth_challenges SET consumed_at=?,password_hash=NULL WHERE id=?", clock.instant(), id)
                auth.complete(c, owner, row.device)
            }
        }
        if (badPassword) throw ApiFailure(401, "INVALID_CREDENTIALS")
        return checkNotNull(result)
    }

    private fun <T : Any> consume(id: String, purposes: Set<String>, secret: String, block: (Connection, Challenge) -> T): T {
        val result = db.transaction { c ->
            val row = challenge(c, id)
            usable(row, purposes)
            if (!MessageDigest.isEqual(row.hash.toByteArray(), digest(id, secret).toByteArray())) {
                execute(c, "UPDATE auth_challenges SET attempts=attempts+1 WHERE id=?", id)
                null // Commit failed attempts; throwing inside the transaction would roll them back.
            } else {
                execute(c, "UPDATE auth_challenges SET consumed_at=?,password_hash=NULL WHERE id=?", clock.instant(), id)
                block(c, row)
            }
        }
        return result ?: throw ApiFailure(400, "CHALLENGE_INVALID")
    }

    private fun challenge(c: Connection, id: String): Challenge {
        if (id.length > 100) throw ApiFailure(400, "CHALLENGE_INVALID")
        val mail = query(c, "SELECT email FROM auth_challenges WHERE id=?", id).use { q ->
            q.executeQuery().use { r -> if (r.next()) r.getString(1) else throw ApiFailure(400, "CHALLENGE_INVALID") }
        }
        if (mail.isNotEmpty()) emailLock(c, mail)
        return query(c, "SELECT * FROM auth_challenges WHERE id=? FOR UPDATE", id).use { q -> q.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(400, "CHALLENGE_INVALID")
            Challenge(id, r.getString("purpose"), r.getString("email"), r.getObject("user_id", UUID::class.java),
                r.getString("secret_hash"), r.getString("password_hash"), r.getString("subject"), r.getString("device_name"),
                r.instant("expires_at"), r.instant("sent_at"), r.getInt("attempts"), r.getTimestamp("consumed_at") != null)
        } }
    }

    private fun usable(row: Challenge, purposes: Set<String>) {
        if (row.purpose !in purposes || row.consumed || row.attempts >= 5 || row.expires <= clock.instant()) throw ApiFailure(400, "CHALLENGE_INVALID")
    }
    private fun insert(c: Connection, purpose: String, email: String = "", user: UUID? = null, secret: String = security.token(),
        password: String? = null, subject: String? = null, device: String? = null, id: String = security.token()): ChallengeDto {
        val now = clock.instant()
        execute(c,"DELETE FROM auth_challenges WHERE expires_at < ?",now.minusSeconds(86400))
        execute(c, "INSERT INTO auth_challenges(id,purpose,email,user_id,secret_hash,password_hash,subject,device_name,created_at,expires_at,sent_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id, purpose, email, user, digest(id, secret), password, subject, device, now, now.plusSeconds(600), now)
        return dto(id, purpose, now.plusSeconds(600))
    }
    private fun dto(id: String, purpose: String, expiry: Instant) = ChallengeDto(id, when (purpose) {
        "LINK" -> "CONFIRM_PASSWORD"; "GOOGLE" -> "GOOGLE"; "RESET" -> "VERIFY_RESET"; else -> "VERIFY_EMAIL"
    }, expiry.toString())
    private fun digest(id: String, secret: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(hmacKey, "HmacSHA256"))
        doFinal("$id:$secret".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun code() = random.nextInt(1_000_000).toString().padStart(6, '0')
    private fun email(value: String) = value.trim().lowercase(Locale.ROOT).also {
        if (it.length > 254 || !Regex("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}$").matches(it)) invalid("email", "INVALID_EMAIL")
    }
    /** Account lookup retains AuthService's legacy email syntax; Gmail-only applies to new registrations. */
    private fun accountEmail(value: String) = value.trim().lowercase(Locale.ROOT).also {
        if (it.length > 254 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it)) invalid("email", "INVALID_EMAIL")
    }
    private fun password(value: String, confirm: String) {
        if (!validNewPassword(value)) invalid("password", "PASSWORD_POLICY")
        if (value != confirm) invalid("confirm_password", "PASSWORD_MISMATCH")
    }
    private fun device(value: String?) { if (value != null && value.length > 100) invalid("device_name", "TOO_LONG") }
    private fun invalid(field: String, code: String): Nothing = throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError(field, code)))
    private fun user(c: Connection, email: String): UUID? = query(c, "SELECT id FROM app_users WHERE email=?", email).use { q ->
        q.executeQuery().use { r -> if (r.next()) r.uuid("id") else null }
    }
    private fun emailLock(c: Connection, email: String) {
        query(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", email).use { q -> q.executeQuery().close() }
    }
    /** Recovery proofs may bypass password lockouts, but every caller must respect administrative locks. */
    private fun lockUser(c: Connection, id: UUID, checkTemporaryLock: Boolean = true) {
        query(c, "SELECT status,locked_until FROM app_users WHERE id=? FOR UPDATE", id).use { q -> q.executeQuery().use { r ->
            if (!r.next()) throw ApiFailure(400, "CHALLENGE_INVALID")
            val until = r.getTimestamp("locked_until")?.toInstant()
            if (r.getString("status") == "LOCKED" || (checkTemporaryLock && until != null && until > clock.instant())) throw ApiFailure(403, "ACCOUNT_LOCKED",
                retryAfter = if (r.getString("status") == "LOCKED") null else until?.let { maxOf(1, it.epochSecond - clock.instant().epochSecond) })
        } }
    }
    private fun mailQuota(c: Connection, email: String, peer: String) {
        for ((bucket, limit) in listOf("mail:email:$email" to 5, "mail:ip:$peer" to 30)) {
            val now = clock.instant()
            query(c, """INSERT INTO auth_rate_limits(bucket_hash,window_start,attempts) VALUES (?,?,1)
                ON CONFLICT(bucket_hash) DO UPDATE SET attempts=CASE WHEN auth_rate_limits.window_start<=? THEN 1 ELSE auth_rate_limits.attempts+1 END,
                window_start=CASE WHEN auth_rate_limits.window_start<=? THEN EXCLUDED.window_start ELSE auth_rate_limits.window_start END RETURNING attempts,window_start""",
                security.digest(bucket), now, now.minusSeconds(3600), now.minusSeconds(3600)).use { q -> q.executeQuery().use { r ->
                r.next(); if (r.getInt("attempts") > limit) throw ApiFailure(429, "RATE_LIMITED", retryAfter = maxOf(1, r.instant("window_start").plusSeconds(3600).epochSecond - now.epochSecond))
            } }
        }
    }
    private fun deliver(email: String, code: String, purpose: String, quiet: Boolean = false) {
        try { mailer.send(email, code, purpose) }
        catch (_: Exception) { if (!quiet) throw ApiFailure(503, "EMAIL_DELIVERY_UNAVAILABLE") }
    }
}
