package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.clear(); Dispatchers.resetMain() }
    private class Gateway : FakeAuthGateway() {
        var logins = 0
        var failure: AuthFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun login(email: String, password: String): AuthSession {
            logins++; gate?.await(); failure?.let { throw it }; return sampleSession()
        }
        override suspend fun me(accessToken: String): AccountProfile { failure?.let { throw it }; return sampleSession().account }
        override suspend fun logout(refreshToken: String) { }
    }
    private fun vm(api: Gateway, store: MemorySessionStore = MemorySessionStore()) = RegisterViewModel(api, store).also {
        models.put("vm", it); it.start()
    }
    private fun fill(vm: RegisterViewModel) { vm.edit("email", "shopper@example.com"); vm.edit("password", " password ") }

    @Test fun startsAtLoginAndRejectsInvalidInputWithoutNetwork() = runTest(dispatcher) {
        val api = Gateway(); val vm = vm(api)
        assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page)
        vm.submit(); assertEquals(setOf("email", "password"), vm.state.value.errors.keys); assertEquals(0, api.logins)
        assertTrue(validateLogin(RegisterUiState(email = "a@example.com", password = " short ")).isEmpty())
    }
    @Test fun loginSavesBeforeHomeAndIgnoresDuplicateClicks() = runTest(dispatcher) {
        val api = Gateway().apply { gate = CompletableDeferred() }; val store = MemorySessionStore(); val vm = vm(api, store)
        fill(vm); vm.submit(); vm.submit(); assertEquals(1, api.logins); assertNull(store.saved)
        api.gate!!.complete(Unit)
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page); assertEquals("owner", vm.state.value.account!!.userId)
        assertNotNull(store.saved); assertEquals("", vm.state.value.password)
    }
    @Test fun saveRetryDoesNotLoginTwiceAndChangingFormClearsPassword() = runTest(dispatcher) {
        val api = Gateway(); val store = MemorySessionStore().apply { failSave = true }; val vm = vm(api, store)
        fill(vm); vm.submit(); assertTrue(vm.state.value.savingSession); assertEquals("", vm.state.value.password)
        store.failSave = false; vm.submit(); assertEquals(1, api.logins); assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
    }
    @Test fun genericCredentialsAndLockedRateLimitSurviveFormSwitch() = runTest(dispatcher) {
        val api = Gateway(); val vm = vm(api); fill(vm)
        api.failure = AuthFailure.Http(401, "INVALID_CREDENTIALS", emptyMap(), 2); vm.submit()
        assertEquals("Email hoặc mật khẩu không đúng.", vm.state.value.message)
        api.failure = AuthFailure.Http(403, "ACCOUNT_LOCKED", emptyMap(), 2); vm.submit()
        assertEquals(2, vm.state.value.retrySeconds); assertTrue(vm.state.value.message!!.contains("khóa tạm"))
        vm.showRegister(); vm.showLogin(); assertEquals("", vm.state.value.password)
        vm.submit(); assertEquals(2, api.logins)
        advanceTimeBy(2000); runCurrent(); assertEquals(0, vm.state.value.retrySeconds)
        fill(vm); api.failure = AuthFailure.Http(429, "RATE_LIMITED", emptyMap(), 3); vm.submit()
        assertEquals(3, vm.state.value.retrySeconds)
    }
    @Test fun networkFailureDoesNotAutoRetryAndOfflineRestoreNeverShowsOwner() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Network() }; val store = MemorySessionStore(); val vm = vm(api, store)
        fill(vm); vm.submit(); advanceTimeBy(60000); runCurrent(); assertEquals(1, api.logins)
        store.saved = sampleSession(); vm.showRegister(); vm.showLogin()
        // A new process has only encrypted credentials; it must verify the owner before showing Home.
        val other = RegisterViewModel(api, store).also { models.put("other", it); it.start() }
        assertEquals(AuthPage.STARTING, other.state.value.page); assertNull(other.state.value.account)
    }
    @Test fun restoredLoginGoesHomeAndLogoutHidesOldOwner() = runTest(dispatcher) {
        val api = Gateway(); val store = MemorySessionStore().apply { saved = sampleSession() }; val vm = vm(api, store)
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
        vm.logout(); assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page); assertNull(vm.state.value.account); assertNull(store.saved)
    }
    @Test fun failedLocalLogoutRetriesRemovalInsteadOfRestoringOldOwner() = runTest(dispatcher) {
        val api = Gateway(); val store = MemorySessionStore().apply { saved = sampleSession() }; val vm = vm(api, store)
        store.failClear = true; vm.logout()
        assertEquals(AuthPage.STARTING, vm.state.value.page); assertNull(vm.state.value.account)
        store.failClear = false; vm.retryRestore()
        assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page); assertNull(store.saved)
    }
    @Test fun lockWithoutRetryAfterDoesNotInventAnUnlockTime() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Http(403, "ACCOUNT_LOCKED", emptyMap(), null) }
        val vm = vm(api); fill(vm); vm.submit()
        assertEquals(0, vm.state.value.retrySeconds)
        assertTrue(vm.state.value.message!!.contains("liên hệ hỗ trợ"))
        assertFalse(vm.state.value.message!!.contains("tạm thời"))
    }
}
