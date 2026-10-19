package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleLifecycleViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    private val session = AuthSession(AccountProfile("owner", "fixture@gmail.com", 3, "BALANCED"),
        "access", "refresh", "session", "2026-11-12T00:00:00Z")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { models.clear(); Dispatchers.resetMain() }
    private class Store : SessionStore {
        var gate: CompletableDeferred<Unit>? = null
        var saved: AuthSession? = null
        var writes = 0
        override suspend fun save(session: AuthSession) { writes++; gate?.await(); saved = session }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }
    private fun model(api: AuthGateway, store: Store) = RegisterViewModel(api, store).also { models.put("vm", it); it.start() }
    private open class GoogleApi : FakeAuthGateway() {
        override suspend fun googleChallenge() = AuthChallenge("challenge", "GOOGLE", "2026-11-12T00:00:00Z", 0, "nonce")
    }
    @Test fun uiCancellationAfterTokenDoesNotCancelBackendOrCreateAnotherSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<AuthSession>()
        var calls = 0
        val store = Store()
        val vm = model(object : GoogleApi() {
            override suspend fun google(id: String, token: String): AuthSession { calls++; return gate.await() }
        }, store)
        val ui = launch { vm.signInGoogle { "token" } }
        assertEquals(1, calls)
        ui.cancelAndJoin()
        assertTrue(vm.state.value.sending)
        vm.signInGoogle { error("Duplicate credential picker must not open") }
        gate.complete(session)
        assertSame(session, store.saved)
        assertEquals(1, calls)
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
    }
    @Test fun uiCancellationDuringSaveRetainsNewAccountDestination() = runTest(dispatcher) {
        val fresh = AuthSession(session.account, "a", "r", "s", session.refreshExpiresAt, true)
        val store = Store().apply { gate = CompletableDeferred() }
        val vm = model(object : GoogleApi() { override suspend fun google(id: String, token: String) = fresh }, store)
        val ui = launch { vm.signInGoogle { "token" } }
        assertEquals(1, store.writes)
        ui.cancelAndJoin()
        assertTrue(vm.state.value.sending)
        store.gate!!.complete(Unit)
        assertSame(fresh, store.saved)
        assertEquals(1, store.writes)
        assertEquals(AuthPage.REGISTER_SUCCESS, vm.state.value.page)
    }
    @Test fun cancellingPickerBeforeTokenPreservesDraftAndAllowsRetry() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        var calls = 0
        val store = Store()
        val vm = model(object : GoogleApi() {
            override suspend fun google(id: String, token: String): AuthSession { calls++; return session }
        }, store)
        vm.edit("email", "draft@gmail.com"); vm.edit("password", "DraftOnlyAb!")
        val ui = launch { vm.signInGoogle { gate.await() } }
        ui.cancelAndJoin()
        assertFalse(vm.state.value.sending)
        assertEquals("draft@gmail.com", vm.state.value.email)
        assertEquals("DraftOnlyAb!", vm.state.value.password)
        assertEquals(0, calls)
        vm.signInGoogle { "token" }
        assertEquals(1, calls)
        assertSame(session, store.saved)
    }
    @Test fun clearingViewModelStillCancelsBackendWork() = runTest(dispatcher) {
        var cancelled = false
        val store = Store()
        val vm = model(object : GoogleApi() {
            override suspend fun google(id: String, token: String): AuthSession {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }, store)
        val ui = launch { vm.signInGoogle { "token" } }
        try {
            models.clear()
            assertTrue(cancelled)
            assertNull(store.saved)
        } finally { ui.cancelAndJoin() }
    }
    @Test fun backendFailureAfterUiCancellationStillUnlocksTheForm() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = model(object : GoogleApi() {
            override suspend fun google(id: String, token: String): AuthSession {
                gate.await(); throw AuthFailure.Http(503, "UNAVAILABLE", emptyMap(), null)
            }
        }, Store())
        val ui = launch { vm.signInGoogle { "token" } }
        ui.cancelAndJoin()
        gate.complete(Unit)
        assertFalse(vm.state.value.sending)
        assertNotNull(vm.state.value.message)
        assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page)
    }
}
