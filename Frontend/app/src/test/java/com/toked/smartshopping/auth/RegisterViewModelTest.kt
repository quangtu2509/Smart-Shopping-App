package com.toked.smartshopping.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.clear(); Dispatchers.resetMain() }
    private val sample = AuthSession(AccountProfile("user", "shopper@gmail.com", 3, "BALANCED"), "test-access", "test-refresh", "session", "2026-11-12T00:00:00Z")

    private class Store : SessionStore {
        var saved: AuthSession? = null
        var saves = 0
        var fail = false
        override suspend fun save(session: AuthSession) { saves++; if (fail) throw java.io.IOException(); saved = session }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }
    private class Gateway(val result: AuthSession) : FakeAuthGateway() {
        var calls = 0
        var failure: AuthFailure? = null
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun register(email: String, password: String, confirmation: String): AuthSession {
            calls++; wait?.await(); failure?.let { throw it }; return result
        }
        override suspend fun me(accessToken: String): AccountProfile { failure?.let { throw it }; return result.account }
    }
    private fun model(gateway: AuthGateway, store: SessionStore): RegisterViewModel =
        RegisterViewModel(gateway, store).also { models.put("test", it); it.start(); if (it.state.value.page == AuthPage.LOGIN_ENTRY) it.showRegister() }
    private fun fill(vm: RegisterViewModel) {
        vm.edit("email", "shopper@gmail.com"); vm.edit("password", " Long password with spaces !")
        vm.edit("confirm_password", " Long password with spaces !")
    }

    @Test fun invalidFieldsNeverCallApi() = runTest(dispatcher) {
        val api = Gateway(sample); val vm = model(api, Store())
        vm.edit("email", "invalid"); vm.edit("password", "short"); vm.edit("confirm_password", "other")
        vm.submit()
        assertEquals(setOf("email", "password", "confirm_password"), vm.state.value.errors.keys)
        assertEquals(0, api.calls)
        assertTrue(validate(RegisterUiState(email = "a@gmail.com", password = "MậtKhẩuA!😀", confirmation = "MậtKhẩuA!😀")).isEmpty())
    }
    @Test fun doubleSubmitCallsOnceThenSavesBeforeNavigation() = runTest(dispatcher) {
        val api = Gateway(sample); api.wait = CompletableDeferred()
        val store = Store(); val vm = model(api, store); fill(vm)
        vm.submit(); vm.submit()
        assertEquals(1, api.calls); assertTrue(vm.state.value.sending); assertNull(store.saved)
        api.wait!!.complete(Unit)
        assertSame(sample, store.saved); assertEquals(AuthPage.REGISTER_SUCCESS, vm.state.value.page)
        assertEquals("", vm.state.value.password); assertEquals("", vm.state.value.confirmation)
    }
    @Test fun storageRetryDoesNotCreateAnotherAccount() = runTest(dispatcher) {
        val api = Gateway(sample); val store = Store().apply { fail = true }
        val vm = model(api, store); fill(vm); vm.submit()
        assertTrue(vm.state.value.savingSession); assertEquals(AuthPage.REGISTER, vm.state.value.page)
        assertEquals("", vm.state.value.password)
        store.fail = false; vm.submit()
        assertEquals(1, api.calls); assertEquals(2, store.saves)
        assertEquals(AuthPage.REGISTER_SUCCESS, vm.state.value.page)
    }
    @Test fun emailConflictAndServerValidationStayOnForm() = runTest(dispatcher) {
        val api = Gateway(sample); val store = Store(); val vm = model(api, store); fill(vm)
        api.failure = AuthFailure.Http(409, "EMAIL_ALREADY_EXISTS", emptyMap(), 60); vm.submit()
        assertTrue(vm.state.value.errors["email"]!!.contains("Email đã được sử dụng")); assertNull(store.saved)
        api.failure = AuthFailure.Http(422, "VALIDATION_ERROR", mapOf("confirm_password" to "PASSWORD_MISMATCH"), 60); vm.submit()
        assertTrue("confirm_password" in vm.state.value.errors)
    }
    @Test fun timeoutIsNotAutomaticallyRetried() = runTest(dispatcher) {
        val api = Gateway(sample); api.failure = AuthFailure.Network()
        val vm = model(api, Store()); fill(vm); vm.submit(); advanceTimeBy(60000); runCurrent()
        assertEquals(1, api.calls); assertFalse(vm.state.value.sending)
        assertTrue(vm.state.value.message!!.contains("thử đăng nhập"))
    }
    @Test fun rateLimitDisablesSubmitUntilCooldown() = runTest(dispatcher) {
        val api = Gateway(sample); api.failure = AuthFailure.Http(429, "RATE_LIMITED", emptyMap(), 2)
        val vm = model(api, Store()); fill(vm); vm.submit(); vm.submit()
        assertEquals(1, api.calls); assertEquals(2, vm.state.value.retrySeconds)
        advanceTimeBy(2000); runCurrent(); assertEquals(0, vm.state.value.retrySeconds)
    }
    @Test fun savedSessionIsVerifiedAndRevokedSessionCleared() = runTest(dispatcher) {
        val api = Gateway(sample); val store = Store().apply { saved = sample }
        val vm = model(api, store); assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
        api.failure = AuthFailure.Http(401, "SESSION_REVOKED", emptyMap(), 60)
        vm.onResume(); assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page); assertNull(store.saved)
    }
}
