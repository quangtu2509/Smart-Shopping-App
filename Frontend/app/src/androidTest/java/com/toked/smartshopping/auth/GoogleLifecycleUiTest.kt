package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GoogleLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store : SessionStore {
        val entered = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        var saved: AuthSession? = null
        override suspend fun save(session: AuthSession) { entered.complete(Unit); gate?.await(); saved = session }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }
    @Test fun activityRecreationDuringGoogleResponseStillSavesExistingAccount() = exercise(waitForStore = false)
    @Test fun activityRecreationDuringStorageStillShowsNewAccountSuccess() = exercise(waitForStore = true)

    private fun exercise(waitForStore: Boolean) {
        val store = Store().apply { if (waitForStore) gate = CompletableDeferred() }
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val session = AuthSession(AccountProfile("fixture-owner", "fixture@gmail.com", 3, "BALANCED"),
            "fixture-access", "fixture-refresh", "fixture-session", "2026-11-12T00:00:00Z", waitForStore)
        var calls = 0
        val api = object : AuthGateway {
            override suspend fun register(email: String, password: String, confirmation: String): AuthSession = error("Unexpected register")
            override suspend fun me(accessToken: String) = session.account
            override suspend fun login(email: String, password: String): AuthSession = error("Unexpected login")
            override suspend fun refresh(session: AuthSession): AuthSession = error("Unexpected refresh")
            override suspend fun logout(refreshToken: String) = error("Unexpected logout")
            override suspend fun sessions(accessToken: String, cursor: String?): AuthSessionPage = error("Unexpected sessions")
            override suspend fun revoke(accessToken: String, sessionId: String) = error("Unexpected revoke")
            override suspend fun logoutAll(accessToken: String) = error("Unexpected logout-all")
            override suspend fun googleChallenge() = AuthChallenge("fixture", "GOOGLE", session.refreshExpiresAt, 0, "fixture-nonce")
            override suspend fun google(id: String, token: String): AuthSession {
                calls++; entered.complete(Unit)
                if (!waitForStore) response.await()
                return session
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RegisterViewModel(api, store) as T
        }
        var retained: RegisterViewModel? = null
        fun attach(activity: ComponentActivity) {
            val vm = ViewModelProvider(activity, factory)[RegisterViewModel::class.java]
            if (retained != null) assertSame(retained, vm) else retained = vm
            vm.start()
            activity.setContent {
                val state by vm.state.collectAsState()
                val scope = rememberCoroutineScope()
                SmartShoppingTheme {
                    AuthContent(state, vm::edit, vm::submit, vm::showLogin, vm::showRegister,
                        vm::skipOnboarding, vm::showProfile, vm::retryRestore,
                        onGoogle = { scope.launch { vm.signInGoogle { "fixture-token" } } })
                }
            }
        }
        compose.activityRule.scenario.onActivity { attach(it) }
        compose.onNodeWithText("Tiếp tục với Google").performScrollTo().performClick()
        compose.waitUntil(10000) { entered.isCompleted && (!waitForStore || store.entered.isCompleted) }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { attach(it) }
        compose.runOnIdle {
            assertTrue(retained!!.state.value.sending)
            assertEquals(1, calls)
            assertNull(store.saved)
            if (waitForStore) store.gate!!.complete(Unit) else response.complete(Unit)
        }
        compose.waitUntil(10000) { retained!!.state.value.page == if (waitForStore) AuthPage.REGISTER_SUCCESS else AuthPage.HOME_ENTRY }
        compose.runOnIdle { assertSame(session, store.saved); assertEquals(1, calls) }
        compose.onNodeWithText(if (waitForStore) "Đăng ký thành công!" else "fixture@gmail.com").assertExists()
    }
}
