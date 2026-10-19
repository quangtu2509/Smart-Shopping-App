package com.toked.smartshopping.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class MemorySessionStore : SessionStore {
    var saved: AuthSession? = null
    var failSave = false
    var failClear = false
    override suspend fun save(session: AuthSession) { if (failSave) throw java.io.IOException(); saved = session }
    override suspend fun load() = saved
    override suspend fun clear() { if (failClear) throw java.io.IOException(); saved = null }
}

internal fun sampleSession(access: String = "old-access", refresh: String = "old-refresh") = AuthSession(
    AccountProfile("owner", "shopper@example.com", 3, "BALANCED"), access, refresh, "session", "2026-11-12T00:00:00Z")

class AuthSessionRepositoryTest {
    private class Gateway : FakeAuthGateway() {
        var refreshes = 0
        var revoked: String? = null
        var failRefresh = false
        var failLogout = false
        var alwaysExpired = false
        var rejectRevoked = false
        var refreshGate: CompletableDeferred<Unit>? = null
        val accesses = mutableListOf<String>()
        override suspend fun me(accessToken: String) = sampleSession().account
        override suspend fun sessions(accessToken: String, cursor: String?): AuthSessionPage {
            accesses += accessToken
            if (rejectRevoked) throw AuthFailure.Http(401, "SESSION_REVOKED", emptyMap(), 1)
            if (accessToken == "old-access" || alwaysExpired) throw AuthFailure.Http(401, "ACCESS_TOKEN_EXPIRED", emptyMap(), 1)
            return AuthSessionPage(emptyList(), null)
        }
        override suspend fun refresh(session: AuthSession): AuthSession {
            refreshes++; refreshGate?.await()
            if (failRefresh) throw AuthFailure.Network()
            return sampleSession("new-access", "new-refresh")
        }
        override suspend fun logout(refreshToken: String) { if (failLogout) throw AuthFailure.Network(); revoked = refreshToken }
        override suspend fun revoke(accessToken: String, sessionId: String) { revoked = sessionId }
        override suspend fun logoutAll(accessToken: String) { revoked = "all" }
    }

    @Test fun concurrentProtectedRequestsShareOneRotationAndPersistBeforeRetry() = runTest {
        val api = Gateway().apply { refreshGate = CompletableDeferred() }
        val store = MemorySessionStore(); val repository = AuthSessionRepository(api, store)
        repository.save(sampleSession())
        val first = async { repository.sessions() }; val second = async { repository.sessions() }
        testScheduler.runCurrent()
        assertEquals(1, api.refreshes); assertNull(store.saved)
        api.refreshGate!!.complete(Unit); first.await(); second.await()
        assertEquals(1, api.refreshes); assertEquals("new-refresh", store.saved!!.refreshToken)
        assertEquals(listOf("old-access", "new-access", "new-access"), api.accesses)
    }

    @Test fun revokedSessionIsClearedWithoutRefresh() = runTest {
        val api = Gateway().apply { rejectRevoked = true }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        try { repository.sessions(); fail() } catch (_: AuthFailure.SessionEnded) { }
        assertNull(store.saved); assertEquals(0, api.refreshes)
    }

    @Test fun lostRefreshResponseCannotReplayOldToken() = runTest {
        val api = Gateway().apply { failRefresh = true }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        repeat(2) { try { repository.sessions(); fail() } catch (_: AuthFailure.SessionEnded) { } }
        assertNull(store.saved); assertEquals(1, api.refreshes)
    }

    @Test fun protectedRequestRetriesAtMostOnce() = runTest {
        val api = Gateway().apply { alwaysExpired = true }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        try { repository.sessions(); fail() } catch (_: AuthFailure.SessionEnded) { }
        assertEquals(2, api.accesses.size); assertEquals(1, api.refreshes); assertNull(store.saved)
    }

    @Test fun storageFailureAfterRotationRevokesNewSessionAndClearsOld() = runTest {
        val api = Gateway(); val store = MemorySessionStore(); val repository = AuthSessionRepository(api, store)
        repository.save(sampleSession()); store.failSave = true
        try { repository.sessions(); fail() } catch (_: AuthFailure.SessionEnded) { }
        assertEquals("new-refresh", api.revoked); assertNull(store.saved)
    }

    @Test fun logoutFailureStillClearsLocalAndReportsUnconfirmedServer() = runTest {
        val api = Gateway().apply { failLogout = true }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        val result = repository.logout()
        assertTrue(result.localCleared); assertFalse(result.serverRevoked); assertNull(store.saved)
    }

    @Test fun revokeOnlyCurrentSessionClearsCredentialsAndLogoutAllUsesOwner() = runTest {
        val api = Gateway(); val store = MemorySessionStore(); val repository = AuthSessionRepository(api, store)
        repository.save(sampleSession()); assertFalse(repository.revoke("other")); assertNotNull(store.saved)
        assertTrue(repository.revoke("session")); assertNull(store.saved)
        repository.save(sampleSession("new-access")); assertTrue(repository.logout(all = true).serverRevoked)
        assertEquals("all", api.revoked); assertNull(store.saved)
    }

    @Test fun restoreRejectsAnotherOwnerInsteadOfShowingCachedOrForeignData() = runTest {
        val api = object : FakeAuthGateway() {
            override suspend fun me(accessToken: String) = AccountProfile("different-owner", "other@example.com", 3, "BALANCED")
        }
        val store = MemorySessionStore().apply { saved = sampleSession() }
        try { AuthSessionRepository(api, store).restore(); fail() } catch (_: AuthFailure.SessionEnded) { }
        assertNull(store.saved)
    }

    @Test fun offlineRestoreKeepsCredentialsButDoesNotReturnCachedAccount() = runTest {
        val api = object : FakeAuthGateway() { override suspend fun me(accessToken: String): AccountProfile = throw AuthFailure.Network() }
        val store = MemorySessionStore().apply { saved = sampleSession() }
        try { AuthSessionRepository(api, store).restore(); fail() } catch (_: AuthFailure.Network) { }
        assertNotNull(store.saved)
    }

    @Test fun cancellationDuringRefreshLeavesNoReplayablePersistedToken() = runTest {
        val api = Gateway().apply { refreshGate = CompletableDeferred() }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        val request = async { repository.sessions() }; testScheduler.runCurrent()
        assertEquals(1, api.refreshes); assertNull(store.saved)
        request.cancelAndJoin(); assertNull(store.saved)
        try { repository.sessions(); fail() } catch (_: AuthFailure.SessionEnded) { }
        assertEquals(1, api.refreshes)
    }

    @Test fun retryingLocalCleanupCannotClaimUnconfirmedServerRevocation() = runTest {
        val api = Gateway().apply { failLogout = true }; val store = MemorySessionStore()
        val repository = AuthSessionRepository(api, store); repository.save(sampleSession())
        store.failClear = true
        val first = repository.logout(); assertFalse(first.serverRevoked); assertFalse(first.localCleared)
        // The real store's durable sign-out marker can already hide the old ciphertext.
        store.saved = null; store.failClear = false
        val second = repository.logout()
        assertTrue(second.localCleared); assertFalse(second.serverRevoked)
    }
}
