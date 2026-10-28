package com.toked.smartshopping.backend

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.*

class AuthFlowsTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var flows: AuthFlows
    private val clock = TestClock()
    private val mail = ConcurrentHashMap<String, String>()
    private var identity = GoogleIdentity("subject-1", "google.shop@gmail.com", true, "")
    private val password = "ShoppingAb!"
    @Before fun setup() {
        val url = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        require(url == "jdbc:postgresql://127.0.0.1:55432/auth00_test")
        db = Database(url,"auth00_test", "")
        db.migrate()
        resetAuthTestData(db)
        clock.now = Instant.parse("2026-10-18T00:00:00Z")
        auth = AuthService(db, clock = clock)
        flows = AuthFlows(db, auth, AuthMailer { email, code, _ -> mail[email] = code }, GoogleVerifier { identity }, ByteArray(32) { 7 }, clock)
    }
    @After fun close() { if (::db.isInitialized) db.close() }
    private fun register(email: String = "shopper@gmail.com") = flows.register(email, password, password, "test", "peer")
    private fun verified(email: String = "shopper@gmail.com"): AuthResult {
        val challenge = register(email)
        return flows.verifyEmail(challenge.challenge_id, mail.getValue(email))
    }
    private fun fail(code: String, action: () -> Unit) = assertFailsWith<ApiFailure>(block = action).also { assertEquals(code, it.code) }

    @Test fun registrationDoesNotCreateUserUntilProofAndCannotReplay() {
        val challenge = register()
        assertEquals("VERIFY_EMAIL", challenge.next_action)
        db.transaction { c -> query(c, "SELECT count(*) FROM app_users").use { q -> q.executeQuery().use { r -> r.next(); assertEquals(0,r.getInt(1)) } } }
        val result = flows.verifyEmail(challenge.challenge_id, mail.getValue("shopper@gmail.com"))
        assertTrue(result.is_new_user)
        assertEquals(result.user, auth.me(result.tokens.access_token).user)
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id, mail.getValue("shopper@gmail.com")) }
        fail("EMAIL_ALREADY_EXISTS") { register() }
    }
    @Test fun onlyExactGmailAndNewPasswordPolicyAreAccepted() {
        for (email in listOf("abc@lmao.ccs","abc@gmail.com.evil.com","abc@googlemail.com")) {
            fail("VALIDATION_ERROR") { register(email) }
        }
        for (bad in listOf("shortA!","lowercase!","UPPERCASE!","NoSymbolsHere","Onlyspaces A")) {
            fail("VALIDATION_ERROR") { flows.register("a@gmail.com", bad, bad, null, "peer") }
        }
        assertTrue(validNewPassword("Abcdefghi!")) // No digit is required.
        assertFalse(validNewPassword("Abcdefghij\u200b")) // Invisible format and combining marks are not punctuation/symbols.
        assertFalse(validNewPassword("Abcdefghij\u0301"))
        fail("VALIDATION_ERROR") { flows.register("a@gmail.com", password, "different", null, "peer") }
    }
    @Test fun otpIsHmacProtectedAndFailedAttemptsCommit() {
        val challenge = register()
        val code = mail.getValue("shopper@gmail.com")
        db.transaction { c -> query(c, "SELECT secret_hash,password_hash FROM auth_challenges WHERE id=?",challenge.challenge_id).use { q -> q.executeQuery().use { r ->
            r.next(); assertNotEquals(Security().digest(code),r.getString(1)); assertEquals(64,r.getString(1).length)
            assertTrue(r.getString(2).startsWith("\$argon2id\$"))
        } } }
        repeat(5) { fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,"not-a-code") } }
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,code) }
        clock.now = clock.now.plusSeconds(61)
        fail("CHALLENGE_INVALID") { flows.resend(challenge.challenge_id,"peer") }
    }
    @Test fun resendRespectsCooldownExpiryAndRetainsAttempts() {
        val challenge = register()
        fail("RATE_LIMITED") { flows.resend(challenge.challenge_id,"peer") }
        repeat(4) { fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,"bad") } }
        clock.now = clock.now.plusSeconds(61)
        val next = flows.resend(challenge.challenge_id,"peer")
        assertEquals(challenge.expires_at,next.expires_at)
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,"bad") }
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,mail.getValue("shopper@gmail.com")) }
    }
    @Test fun expiryAndCrossPurposeAreEnforced() {
        val challenge = register()
        fail("CHALLENGE_INVALID") { flows.verifyReset(challenge.challenge_id,mail.getValue("shopper@gmail.com")) }
        clock.now = clock.now.plusSeconds(600)
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,mail.getValue("shopper@gmail.com")) }
    }
    @Test fun resentCodeReplacesPreviousCodeWithoutExtendingExpiry() {
        val challenge = register()
        val old = mail.getValue("shopper@gmail.com")
        clock.now = clock.now.plusSeconds(61)
        val resent = flows.resend(challenge.challenge_id,"peer")
        val updated = mail.getValue("shopper@gmail.com")
        assertNotEquals(old,updated)
        assertEquals(challenge.expires_at,resent.expires_at)
        fail("CHALLENGE_INVALID") { flows.verifyEmail(challenge.challenge_id,old) }
        assertTrue(flows.verifyEmail(challenge.challenge_id,updated).is_new_user)
    }
    @Test fun emailQuotaCannotBeBypassedUsingAnotherIpOrChallenge() {
        repeat(5) { flows.register("quota@gmail.com",password,password,null,"peer-$it") }
        fail("RATE_LIMITED") { flows.register("quota@gmail.com",password,password,null,"different") }
    }
    @Test fun peerQuotaAppliesAcrossDifferentRecipients() {
        repeat(30) { flows.forgot("unknown-$it@gmail.com","same-peer") }
        fail("RATE_LIMITED") { flows.forgot("unknown-next@gmail.com","same-peer") }
        assertTrue(mail.isEmpty())
    }
    @Test fun profileFailureRollsBackVerificationAndAllowsSafeRetry() {
        val challenge = register()
        val code = mail.getValue("shopper@gmail.com")
        db.transaction { execute(it,"ALTER TABLE profiles ADD CONSTRAINT verification_failure CHECK (max_stores=2)") }
        try {
            assertFailsWith<java.sql.SQLException> { flows.verifyEmail(challenge.challenge_id,code) }
            db.transaction { c -> query(c,"SELECT count(*) FROM app_users").use { q -> q.executeQuery().use { r -> r.next(); assertEquals(0,r.getInt(1)) } } }
        } finally { db.transaction { execute(it,"ALTER TABLE profiles DROP CONSTRAINT verification_failure") } }
        assertTrue(flows.verifyEmail(challenge.challenge_id,code).is_new_user)
    }
    @Test fun unknownEmailRecoveryHasSameShapeAndDoesNotSendMail() {
        val challenge = flows.forgot("unknown@gmail.com","peer")
        assertEquals("VERIFY_RESET",challenge.next_action)
        assertFalse(mail.containsKey("unknown@gmail.com"))
        fail("CHALLENGE_INVALID") { flows.verifyReset(challenge.challenge_id,"123456") }
    }
    @Test fun resetRevokesSessionsGrantsAndChallengesAndKeepsUser() {
        val initial = verified()
        val other = auth.login(initial.user.email,password,null)
        val first = flows.forgot(initial.user.email,"peer")
        val grant = flows.verifyReset(first.challenge_id,mail.getValue(initial.user.email))
        val second = flows.forgot(initial.user.email,"peer")
        val secondGrant = flows.verifyReset(second.challenge_id,mail.getValue(initial.user.email))
        val newPassword = "NewShopping!"
        flows.reset(grant.reset_token,newPassword,newPassword)
        fail("REFRESH_TOKEN_INVALID") { auth.refresh(initial.tokens.refresh_token) }
        fail("SESSION_REVOKED") { auth.me(other.tokens.access_token) }
        fail("CHALLENGE_INVALID") { flows.reset(secondGrant.reset_token,password,password) }
        fail("CHALLENGE_INVALID") { flows.reset(grant.reset_token,password,password) }
        fail("INVALID_CREDENTIALS") { auth.login(initial.user.email,password,null) }
        assertEquals(initial.user.id,auth.login(initial.user.email,newPassword,null).user.id)
    }
    @Test fun legacyNonGmailAccountVerifiesWithoutChangingIdentityOrPassword() {
        val initial = auth.register("legacy@example.com",password,password,null)
        db.transaction { execute(it,"UPDATE app_users SET email_verified_at=NULL,password_hash=? WHERE id=?",Security().passwordHash("old"),java.util.UUID.fromString(initial.user.id)) }
        fail("SESSION_REVOKED") { auth.me(initial.tokens.access_token) }
        fail("INVALID_CREDENTIALS") { flows.login(initial.user.email,"wrong",null,"peer") }
        val challenge = flows.login(initial.user.email,"old",null,"peer") as ChallengeDto
        val result = flows.verifyEmail(challenge.challenge_id,mail.getValue(initial.user.email))
        assertEquals(initial.user.id,result.user.id)
        assertFalse(result.is_new_user)
        assertEquals(initial.user.id,auth.login(initial.user.email,"old",null).user.id)
    }
    @Test fun legacyEmailShapesCanVerifyWithoutLosingOwnerProfileOrOldPassword() {
        for (email in listOf("legacy@example.xn--p1ai", "legacy@example.c0m")) {
            val initial = legacyAccount(email)
            val challenge = flows.login("  ${email.uppercase(java.util.Locale.ROOT)}  ","old",null,"peer") as ChallengeDto
            val result = flows.verifyEmail(challenge.challenge_id,mail.getValue(email))
            assertEquals(initial.user.id,result.user.id)
            assertEquals(2,result.profile.max_stores)
            assertFalse(result.is_new_user)
            assertEquals(initial.user.id,auth.login(email,"old",null).user.id)
        }
        fail("VALIDATION_ERROR") { register("new@example.xn--p1ai") }
    }
    @Test fun legacyEmailShapesCanRecoverWithoutCreatingAnotherAccount() {
        for (email in listOf("recover@example.xn--p1ai", "recover@example.c0m")) {
            val initial = legacyAccount(email)
            val challenge = flows.forgot("  ${email.uppercase(java.util.Locale.ROOT)}  ","peer")
            val grant = flows.verifyReset(challenge.challenge_id,mail.getValue(email))
            flows.reset(grant.reset_token,password,password)
            val result = auth.login(email,password,null)
            assertEquals(initial.user.id,result.user.id)
            assertEquals(2,result.profile.max_stores)
            fail("INVALID_CREDENTIALS") { auth.login(email,"old",null) }
        }
    }
    @Test fun recoveryStillRejectsMalformedEmailAndHidesUnknownLegacyAddresses() {
        for (email in listOf("invalid", "a@@example.com", "a @example.com", "a".repeat(255)+"@example.com")) {
            fail("VALIDATION_ERROR") { flows.forgot(email,"peer") }
        }
        assertEquals("VERIFY_RESET",flows.forgot("unknown@example.xn--p1ai","peer").next_action)
        assertTrue(mail.isEmpty())
    }
    @Test fun passwordAttemptsCannotLockGoogleOnlyAccountOrRevokeItsSession() {
        val first = flows.googleChallenge(); identity = identity.copy(nonce=first.nonce!!)
        val initial = flows.google(first.challenge_id,"fake",null) as AuthResult
        repeat(12) { fail("INVALID_CREDENTIALS") { auth.login(initial.user.email,"wrong",null) } }
        db.transaction { c -> query(c,"SELECT failed_logins,locked_until,password_hash FROM app_users WHERE id=?",java.util.UUID.fromString(initial.user.id)).use { q -> q.executeQuery().use { r ->
            assertTrue(r.next()); assertEquals(0,r.getInt("failed_logins")); assertNull(r.getTimestamp("locked_until")); assertNull(r.getString("password_hash"))
        } } }
        assertEquals(initial.user.id,auth.me(initial.tokens.access_token).user.id)
        assertEquals(initial.tokens.auth_session_id,auth.refresh(initial.tokens.refresh_token).auth_session_id)
        val next = flows.googleChallenge(); identity = identity.copy(nonce=next.nonce!!)
        assertEquals(initial.user.id,(flows.google(next.challenge_id,"fake",null) as AuthResult).user.id)
    }
    @Test fun googleOnlyAdministrativeLockStillBlocksGoogle() {
        val first = flows.googleChallenge(); identity = identity.copy(nonce=first.nonce!!)
        val initial = flows.google(first.challenge_id,"fake",null) as AuthResult
        db.transaction { execute(it,"UPDATE app_users SET status='LOCKED' WHERE id=?",java.util.UUID.fromString(initial.user.id)) }
        fail("SESSION_REVOKED") { auth.me(initial.tokens.access_token) }
        val next = flows.googleChallenge(); identity = identity.copy(nonce=next.nonce!!)
        fail("ACCOUNT_LOCKED") { flows.google(next.challenge_id,"fake",null) }
    }
    @Test fun correctRecoveryOtpCanUnlockTemporaryPasswordLock() {
        val initial = verified()
        temporarilyLock(initial.user.email)
        val challenge = flows.forgot(initial.user.email,"peer")
        val grant = flows.verifyReset(challenge.challenge_id,mail.getValue(initial.user.email))
        flows.reset(grant.reset_token,"RecoveredAb!","RecoveredAb!")
        assertEquals(initial.user.id,auth.login(initial.user.email,"RecoveredAb!",null).user.id)
        fail("INVALID_CREDENTIALS") { auth.login(initial.user.email,password,null) }
        fail("REFRESH_TOKEN_INVALID") { auth.refresh(initial.tokens.refresh_token) }
        fail("CHALLENGE_INVALID") { flows.reset(grant.reset_token,password,password) }
    }
    @Test fun resetGrantSurvivesTemporaryLockIntroducedAfterOtpProof() {
        val initial = verified()
        val challenge = flows.forgot(initial.user.email,"peer")
        val grant = flows.verifyReset(challenge.challenge_id,mail.getValue(initial.user.email))
        temporarilyLock(initial.user.email)
        flows.reset(grant.reset_token,"RecoveredAb!","RecoveredAb!")
        db.transaction { c -> query(c,"SELECT failed_logins,locked_until,status FROM app_users WHERE id=?",java.util.UUID.fromString(initial.user.id)).use { q -> q.executeQuery().use { r ->
            assertTrue(r.next()); assertEquals(0,r.getInt("failed_logins")); assertNull(r.getTimestamp("locked_until")); assertEquals("ACTIVE",r.getString("status"))
        } } }
        assertEquals(initial.user.id,auth.login(initial.user.email,"RecoveredAb!",null).user.id)
    }
    @Test fun administrativeLockBlocksBothRecoveryStepsWithoutChangingPassword() {
        val initial = verified()
        val owner = java.util.UUID.fromString(initial.user.id)
        val challenge = flows.forgot(initial.user.email,"peer")
        val code = mail.getValue(initial.user.email)
        db.transaction { execute(it,"UPDATE app_users SET status='LOCKED' WHERE id=?",owner) }
        fail("ACCOUNT_LOCKED") { flows.verifyReset(challenge.challenge_id,code) }
        db.transaction { execute(it,"UPDATE app_users SET status='ACTIVE' WHERE id=?",owner) }
        val grant = flows.verifyReset(challenge.challenge_id,code)
        db.transaction { execute(it,"UPDATE app_users SET status='LOCKED' WHERE id=?",owner) }
        fail("ACCOUNT_LOCKED") { flows.reset(grant.reset_token,"RecoveredAb!","RecoveredAb!") }
        db.transaction { execute(it,"UPDATE app_users SET status='ACTIVE' WHERE id=?",owner) }
        assertEquals(initial.user.id,auth.login(initial.user.email,password,null).user.id)
        fail("INVALID_CREDENTIALS") { auth.login(initial.user.email,"RecoveredAb!",null) }
    }
    private fun legacyAccount(email: String): AuthResult {
        val initial = auth.register(email,password,password,null)
        db.transaction {
            val owner = java.util.UUID.fromString(initial.user.id)
            execute(it,"UPDATE app_users SET email_verified_at=NULL,password_hash=? WHERE id=?",Security().passwordHash("old"),owner)
            execute(it,"UPDATE profiles SET max_stores=2 WHERE user_id=?",owner)
        }
        return initial
    }
    private fun temporarilyLock(email: String) {
        repeat(10) { fail("INVALID_CREDENTIALS") { auth.login(email,"wrong",null) } }
        fail("ACCOUNT_LOCKED") { auth.login(email,password,null) }
    }
    @Test fun googleNewAccountHasNoPasswordAndNonceIsSingleUse() {
        val challenge = flows.googleChallenge()
        identity = identity.copy(nonce = challenge.nonce!!)
        val result = flows.google(challenge.challenge_id,"verified-by-fake",null) as AuthResult
        assertTrue(result.is_new_user)
        fail("CHALLENGE_INVALID") { flows.google(challenge.challenge_id,"verified-by-fake",null) }
        assertTrue(mail.isEmpty())
        val next = flows.googleChallenge(); identity = identity.copy(nonce = next.nonce!!)
        assertEquals(result.user.id,(flows.google(next.challenge_id,"verified-by-fake",null) as AuthResult).user.id)
        flows.forgot(result.user.email,"peer")
        assertTrue(mail.isEmpty())
        fail("INVALID_CREDENTIALS") { auth.login(result.user.email,"any",null) }
    }
    @Test fun googleCompletionDoesNotWaitForUnrelatedEmptyEmailLock() {
        val challenge = flows.googleChallenge(); identity = identity.copy(nonce = challenge.nonce!!)
        val executor = Executors.newSingleThreadExecutor()
        try {
            db.transaction { c ->
                query(c,"SELECT pg_advisory_xact_lock(hashtextextended('',0))").use { it.executeQuery().close() }
                val request = executor.submit<AuthResult> { flows.google(challenge.challenge_id,"fake",null) as AuthResult }
                val result = try { request.get(3, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.TimeoutException) { fail("Google completion waited for another transaction's empty-email lock") }
                assertEquals(identity.email,result.user.email)
            }
        } finally { executor.shutdown(); assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun concurrentGoogleChallengesForSameEmailKeepOneOwnerAndProfile() {
        val first = flows.googleChallenge(); val second = flows.googleChallenge()
        val concurrent = AuthFlows(db,auth,AuthMailer { _,_,_ -> },GoogleVerifier { token ->
            identity.copy(nonce = if (token == "first") first.nonce!! else second.nonce!!)
        },ByteArray(32) { 7 },clock)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(executor.submit<AuthResult> { concurrent.google(first.challenge_id,"first",null) as AuthResult },
                executor.submit<AuthResult> { concurrent.google(second.challenge_id,"second",null) as AuthResult })
                .map { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(results[0].user.id,results[1].user.id)
            db.transaction { c -> query(c,"SELECT (SELECT count(*) FROM app_users),(SELECT count(*) FROM profiles),(SELECT count(*) FROM auth_sessions)").use { q -> q.executeQuery().use { r ->
                r.next(); assertEquals(1,r.getInt(1)); assertEquals(1,r.getInt(2)); assertEquals(2,r.getInt(3))
            } } }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun concurrentGoogleReplayConsumesNonceOnlyOnce() {
        val challenge = flows.googleChallenge(); identity = identity.copy(nonce = challenge.nonce!!)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<String> {
                try { flows.google(challenge.challenge_id,"fake",null); "SUCCESS" }
                catch (failure: ApiFailure) { failure.code }
            } }.map { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf("CHALLENGE_INVALID","SUCCESS"),results.sorted())
            db.transaction { c -> query(c,"SELECT count(*) FROM auth_sessions").use { q -> q.executeQuery().use { r -> r.next(); assertEquals(1,r.getInt(1)) } } }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun googleWrongNonceAndUnverifiedAddressAreRejected() {
        val challenge = flows.googleChallenge()
        identity = identity.copy(nonce = "wrong")
        fail("CHALLENGE_INVALID") { flows.google(challenge.challenge_id,"fake",null) }
        identity = identity.copy(nonce = challenge.nonce!!,verified = false)
        fail("GOOGLE_TOKEN_INVALID") { flows.google(challenge.challenge_id,"fake",null) }
    }
    @Test fun googleCollisionRequiresPasswordAndPreservesOwner() {
        val existing = auth.register(identity.email,password,password,null)
        db.transaction { execute(it,"UPDATE app_users SET email_verified_at=NULL WHERE id=?",java.util.UUID.fromString(existing.user.id)) }
        val nonce = flows.googleChallenge(); identity = identity.copy(nonce = nonce.nonce!!)
        val link = flows.google(nonce.challenge_id,"fake",null) as ChallengeDto
        assertEquals("CONFIRM_PASSWORD",link.next_action)
        fail("INVALID_CREDENTIALS") { flows.link(link.challenge_id,"wrong") }
        val result = flows.link(link.challenge_id,password)
        assertEquals(existing.user.id,result.user.id)
        assertFalse(result.is_new_user)
        fail("CHALLENGE_INVALID") { flows.link(link.challenge_id,password) }
    }
    @Test fun concurrentVerificationCreatesOnlyOneAccountAndSession() {
        val challenge = register()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { pool.submit<Boolean> {
                try { flows.verifyEmail(challenge.challenge_id,mail.getValue("shopper@gmail.com")); true }
                catch (_: ApiFailure) { false }
            } }
            assertEquals(1,tasks.count { it.get() })
        } finally { pool.shutdownNow() }
        db.transaction { c -> query(c,"SELECT count(*) FROM auth_sessions").use { q -> q.executeQuery().use { r -> r.next(); assertEquals(1,r.getInt(1)) } } }
    }
    @Test fun deliveryFailureDoesNotCreateActiveAccountOrLeakProviderDetails() {
        val broken = AuthFlows(db,auth,AuthMailer { _,_,_ -> error("secret-provider-detail") },GoogleVerifier { identity },ByteArray(32),clock)
        fail("EMAIL_DELIVERY_UNAVAILABLE") { broken.register("mailfail@gmail.com",password,password,null,"peer") }
        assertFalse(broken.forgot("mailfail@gmail.com","peer").toString().contains("mailfail"))
    }
    @Test fun repeatedGoogleLinkPasswordsCannotBypassGlobalAccountLock() {
        val owner = auth.register(identity.email,password,password,null)
        repeat(2) {
            val nonce = flows.googleChallenge(); identity = identity.copy(nonce=nonce.nonce!!)
            val link = flows.google(nonce.challenge_id,"fake",null) as ChallengeDto
            repeat(5) { fail("INVALID_CREDENTIALS") { flows.link(link.challenge_id,"wrong") } }
        }
        fail("SESSION_REVOKED") { auth.me(owner.tokens.access_token) }
        fail("ACCOUNT_LOCKED") { auth.login(identity.email,password,null) }
    }
    private class TestClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
