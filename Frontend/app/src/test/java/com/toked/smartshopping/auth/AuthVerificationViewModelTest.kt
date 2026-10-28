package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthVerificationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { models.clear(); Dispatchers.resetMain() }
    private val challenge = AuthChallenge("challenge","VERIFY_EMAIL","2026-11-12T00:00:00Z",60)
    private fun session(fresh: Boolean = true) = AuthSession(AccountProfile("owner","shopper@gmail.com",3,"BALANCED"),"a","r","s","2026-11-12T00:00:00Z",fresh)
    private class Store : SessionStore {
        var value: AuthSession? = null
        var fail = false
        override suspend fun save(session: AuthSession) { if (fail) error("storage"); value = session }
        override suspend fun load() = value
        override suspend fun clear() { value = null }
    }
    private fun model(api: AuthGateway, store: Store) = RegisterViewModel(api,store).also { models.put("vm",it); it.start() }
    private fun fill(vm: RegisterViewModel) { vm.edit("email","shopper@gmail.com"); vm.edit("password","ShoppingAb!"); vm.edit("confirm_password","ShoppingAb!") }

    @Test fun signupWaitsForOtpThenSuccessAndExplicitContinue() = runTest(dispatcher) {
        val store = Store()
        val api = object : FakeAuthGateway() {
            override suspend fun register(email: String,password: String,confirmation: String): AuthSession = throw AuthFailure.NextStep(challenge)
            override suspend fun verifyEmail(id: String,code: String): AuthSession { assertEquals("123456",code); return session() }
        }
        val vm = model(api,store); vm.showRegister(); fill(vm); vm.submit()
        assertEquals(AuthPage.VERIFY_EMAIL,vm.state.value.page); assertNull(store.value)
        assertEquals("",vm.state.value.password); assertEquals(60,vm.state.value.resendSeconds)
        vm.edit("code","123456"); vm.submit()
        assertEquals(AuthPage.REGISTER_SUCCESS,vm.state.value.page); assertNotNull(store.value)
        vm.continueAfterRegistration(); assertEquals(AuthPage.ONBOARDING,vm.state.value.page)
    }
    @Test fun completedOtpRetriesOnlyDeviceStorage() = runTest(dispatcher) {
        var proofs = 0
        val store = Store().apply { fail = true }
        val api = object : FakeAuthGateway() {
            override suspend fun login(email: String,password: String): AuthSession = throw AuthFailure.NextStep(challenge)
            override suspend fun verifyEmail(id: String,code: String): AuthSession { proofs++; return session(false) }
        }
        val vm = model(api,store); fill(vm); vm.submit(); vm.edit("code","123456"); vm.submit()
        assertTrue(vm.state.value.savingSession); assertEquals(1,proofs)
        store.fail = false; vm.submit()
        assertEquals(1,proofs); assertEquals(AuthPage.HOME_ENTRY,vm.state.value.page)
        assertEquals(AuthUiEvent.Notice("Đăng nhập thành công"),vm.events.first())
    }
    @Test fun forgotResetClearsSecretAndReturnsToLoginWithoutSession() = runTest(dispatcher) {
        var resetCalls = 0
        val store = Store()
        val api = object : FakeAuthGateway() {
            override suspend fun forgot(email: String) = AuthChallenge("reset","VERIFY_RESET",challenge.expiresAt,60)
            override suspend fun verifyReset(id: String,code: String) = "reset-secret"
            override suspend fun reset(token: String,password: String,confirm: String) { assertEquals("reset-secret",token); resetCalls++ }
        }
        val vm = model(api,store); vm.edit("email","shopper@gmail.com"); vm.showForgot(); vm.submit()
        assertEquals(AuthPage.VERIFY_RESET,vm.state.value.page)
        vm.edit("code","123456"); vm.submit(); assertEquals(AuthPage.RESET_PASSWORD,vm.state.value.page)
        assertFalse(vm.state.value.passwordResetComplete)
        fill(vm); vm.submit()
        assertEquals(1,resetCalls); assertEquals(AuthPage.LOGIN_ENTRY,vm.state.value.page)
        assertEquals("",vm.state.value.password); assertNull(store.value)
        assertTrue(vm.state.value.passwordResetComplete)
        vm.showLogin()
        assertFalse(vm.state.value.passwordResetComplete)
        assertEquals("shopper@gmail.com", vm.state.value.email)
        assertNull(store.value)
    }
    @Test fun resendWaitsButDoesNotBlockVerification() = runTest(dispatcher) {
        var resends = 0
        val store = Store()
        val api = object : FakeAuthGateway() {
            override suspend fun login(email: String,password: String): AuthSession = throw AuthFailure.NextStep(challenge)
            override suspend fun resend(id: String): AuthChallenge { resends++; return challenge }
        }
        val vm = model(api,store); fill(vm); vm.submit(); vm.resend(); assertEquals(0,resends)
        advanceTimeBy(60000); runCurrent(); vm.resend(); assertEquals(1,resends)
        assertEquals(60,vm.state.value.resendSeconds)
    }
    @Test fun googleCancellationPreservesDraftAndCanRetry() = runTest(dispatcher) {
        val api = object : FakeAuthGateway() {
            override suspend fun googleChallenge() = AuthChallenge("google","GOOGLE",challenge.expiresAt,0,"nonce")
            override suspend fun google(id: String,token: String) = session()
        }
        val vm = model(api,Store()); fill(vm)
        vm.signInGoogle { throw AuthFailure.GoogleCancelled() }
        assertFalse(vm.state.value.sending); assertEquals("ShoppingAb!",vm.state.value.password)
        vm.signInGoogle { assertEquals("nonce",it); "token" }
        assertEquals(AuthPage.REGISTER_SUCCESS,vm.state.value.page)
    }
    @Test fun googleCollisionRequiresPasswordBeforeSession() = runTest(dispatcher) {
        val store = Store()
        val api = object : FakeAuthGateway() {
            override suspend fun googleChallenge() = AuthChallenge("g","GOOGLE",challenge.expiresAt,0,"nonce")
            override suspend fun google(id: String,token: String): AuthSession = throw AuthFailure.NextStep(AuthChallenge("link","CONFIRM_PASSWORD",challenge.expiresAt,0))
            override suspend fun linkGoogle(id: String,password: String): AuthSession { assertEquals("existing",password); return session(false) }
        }
        val vm = model(api,store); vm.signInGoogle { "token" }
        assertEquals(AuthPage.LINK_GOOGLE,vm.state.value.page); assertNull(store.value)
        vm.edit("password","existing"); vm.submit()
        assertEquals(AuthPage.HOME_ENTRY,vm.state.value.page); assertNotNull(store.value)
    }
    @Test fun policyRequiresThreeClassesAndExactGmailButNotDigits() {
        assertFalse(validPassword("Abcdefghij\u200b")); assertFalse(validPassword("Abcdefghij\u0301"))
        assertTrue(validPassword("Abcdefghi!")); assertFalse(validPassword("Abcdefghi "))
        assertFalse(validPassword("abcdefghij!")); assertFalse(validPassword("ABCDEFGHIJ!"))
        assertTrue(validate(RegisterUiState(email="a@gmail.com.evil",password="Abcdefghi!",confirmation="Abcdefghi!")).containsKey("email"))
        assertTrue(validateLogin(RegisterUiState(email="old@example.com",password="old")).isEmpty())
    }
}
