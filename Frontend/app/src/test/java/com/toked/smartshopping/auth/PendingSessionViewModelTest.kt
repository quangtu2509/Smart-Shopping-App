package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingSessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { models.clear(); Dispatchers.resetMain() }

    private enum class Path { LOGIN, REGISTER, LOGIN_OTP, REGISTER_OTP, GOOGLE_EXISTING, GOOGLE_NEW, GOOGLE_LINK }
    private class Store : SessionStore {
        var fail = true
        var gate: CompletableDeferred<Unit>? = null
        var attempts = 0
        var saved: AuthSession? = null
        override suspend fun save(session: AuthSession) {
            attempts++
            gate?.await()
            if (fail) error("fixture storage unavailable")
            saved = session
        }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    @Test fun passwordLoginRetainsSessionUntilStorageRetrySucceeds() = runTest(dispatcher) { exercise(Path.LOGIN) }
    @Test fun registrationRetainsSessionAndSuccessDestination() = runTest(dispatcher) { exercise(Path.REGISTER) }
    @Test fun loginOtpIsNotReplayedAfterStorageFailure() = runTest(dispatcher) { exercise(Path.LOGIN_OTP) }
    @Test fun registrationOtpRetainsSuccessDestination() = runTest(dispatcher) { exercise(Path.REGISTER_OTP) }
    @Test fun existingGoogleSessionDoesNotRepeatCredentialFlow() = runTest(dispatcher) { exercise(Path.GOOGLE_EXISTING) }
    @Test fun newGoogleSessionRetainsSuccessDestination() = runTest(dispatcher) { exercise(Path.GOOGLE_NEW) }
    @Test fun googleLinkProofIsNotReplayedAfterStorageFailure() = runTest(dispatcher) { exercise(Path.GOOGLE_LINK) }

    private suspend fun exercise(path: Path) {
        val fresh = path in setOf(Path.REGISTER, Path.REGISTER_OTP, Path.GOOGLE_NEW)
        val session = AuthSession(AccountProfile("owner", "fixture@gmail.com", 2, "BALANCED"),
            "fixture-access", "fixture-refresh", "fixture-session", "2026-11-12T00:00:00Z", fresh)
        val calls = mutableMapOf<String, Int>()
        fun called(name: String) { calls[name] = calls.getOrDefault(name, 0) + 1 }
        val challenge = AuthChallenge("fixture-challenge", "VERIFY_EMAIL", "2026-11-12T00:00:00Z", 0)
        val api = object : FakeAuthGateway() {
            override suspend fun login(email: String, password: String): AuthSession {
                called("login")
                if (path == Path.LOGIN_OTP) throw AuthFailure.NextStep(challenge)
                return session
            }
            override suspend fun register(email: String, password: String, confirmation: String): AuthSession {
                called("register")
                if (path == Path.REGISTER_OTP) throw AuthFailure.NextStep(challenge)
                return session
            }
            override suspend fun verifyEmail(id: String, code: String): AuthSession { called("otp"); return session }
            override suspend fun googleChallenge(): AuthChallenge { called("nonce"); return AuthChallenge(challenge.id, "GOOGLE", challenge.expiresAt, 0, "fixture-nonce") }
            override suspend fun google(id: String, token: String): AuthSession {
                called("google")
                if (path == Path.GOOGLE_LINK) throw AuthFailure.NextStep(AuthChallenge(challenge.id, "CONFIRM_PASSWORD", challenge.expiresAt, 0))
                return session
            }
            override suspend fun linkGoogle(id: String, password: String): AuthSession { called("link"); return session }
        }
        val store = Store()
        val vm = RegisterViewModel(api, store).also { models.put("vm", it); it.start() }
        if (path in setOf(Path.GOOGLE_EXISTING, Path.GOOGLE_NEW, Path.GOOGLE_LINK)) {
            vm.signInGoogle { called("credentials"); "fixture-token" }
            if (path == Path.GOOGLE_LINK) { vm.edit("password", "ExistingAb!"); vm.submit() }
        } else {
            if (fresh) vm.showRegister()
            vm.edit("email", "fixture@gmail.com"); vm.edit("password", "FixtureAb!"); vm.edit("confirm_password", "FixtureAb!")
            vm.submit()
            if (path in setOf(Path.LOGIN_OTP, Path.REGISTER_OTP)) { vm.edit("code", "123456"); vm.submit() }
        }
        assertTrue(vm.state.value.savingSession)
        assertFalse(vm.state.value.sending)
        assertNull(store.saved)
        assertNull(vm.state.value.account)
        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.confirmation)
        assertEquals("", vm.state.value.code)
        val completedProofs = calls.toMap()
        assertTrue(completedProofs.isNotEmpty())
        fun assertNavigationBlocked() {
            val before = vm.state.value
            vm.showRegister(); assertEquals(before, vm.state.value)
            vm.showLogin(); assertEquals(before, vm.state.value)
            vm.showForgot(); assertEquals(before, vm.state.value)
        }
        assertNavigationBlocked()
        // A second failed save must retain the same proof and allow another local retry.
        vm.submit()
        assertTrue(vm.state.value.savingSession)
        assertNavigationBlocked()
        store.fail = false
        store.gate = CompletableDeferred()
        vm.submit()
        assertTrue(vm.state.value.sending)
        assertNavigationBlocked()
        vm.submit()
        assertEquals(3, store.attempts)
        assertNull(store.saved)
        store.gate!!.complete(Unit)
        assertSame(session, store.saved)
        assertEquals(completedProofs, calls)
        assertEquals(if (fresh) AuthPage.REGISTER_SUCCESS else AuthPage.HOME_ENTRY, vm.state.value.page)
        assertEquals(session.account, vm.state.value.account)
        assertFalse(vm.state.value.savingSession)
        assertFalse(vm.state.value.sending)
    }
}
