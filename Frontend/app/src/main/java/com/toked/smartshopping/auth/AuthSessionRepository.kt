package com.toked.smartshopping.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LogoutOutcome(val serverRevoked: Boolean, val localCleared: Boolean)

/** One owner serializes refresh, token persistence and protected requests. Callers own cancellation. */
class AuthSessionRepository(private val api: AuthGateway, private val store: SessionStore) {
    private val mutex = Mutex()
    private var session: AuthSession? = null
    private var lastLogoutConfirmed = false

    suspend fun save(value: AuthSession) = mutex.withLock {
        store.save(value)
        session = value
        lastLogoutConfirmed = false
    }

    suspend fun restore(): AuthSession? = mutex.withLock {
        session = try { store.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { endSession() }
        if (session == null) return@withLock null
        lastLogoutConfirmed = false
        val account = authorized { api.me(it) }
        val current = session ?: throw AuthFailure.SessionEnded()
        if (account.userId != current.account.userId) endSession()
        val updated = AuthSession(account, current.accessToken, current.refreshToken, current.sessionId, current.refreshExpiresAt)
        store.save(updated)
        session = updated
        updated
    }

    suspend fun sessions(cursor: String? = null): AuthSessionPage = mutex.withLock {
        authorized { api.sessions(it, cursor) }
    }

    suspend fun shoppingProfile(): ShoppingProfile = mutex.withLock { authorized { api.shoppingProfile(it) } }
    suspend fun saveShoppingProfile(patch: ProfilePatch): ShoppingProfile = mutex.withLock { authorized { api.saveShoppingProfile(it, patch) } }
    suspend fun references(kind: String, cursor: String?): ReferencePage = mutex.withLock { authorized { api.references(it, kind, cursor) } }

    suspend fun revoke(id: String): Boolean = mutex.withLock {
        val current = session ?: throw AuthFailure.SessionEnded()
        authorized { api.revoke(it, id) }
        val revokedCurrent = current.sessionId == id
        if (revokedCurrent) { session = null; store.clear() }
        revokedCurrent
    }

    suspend fun logout(all: Boolean = false): LogoutOutcome = mutex.withLock {
        var serverRevoked = lastLogoutConfirmed
        var localCleared = false
        try {
            val current = session ?: store.load()
            if (current != null) {
                session = current
                if (all) authorized { api.logoutAll(it) } else api.logout(current.refreshToken)
                serverRevoked = true
                lastLogoutConfirmed = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            serverRevoked = false
            lastLogoutConfirmed = false
        }
        finally {
            session = null
            withContext(NonCancellable) {
                try { store.clear(); localCleared = true } catch (_: Exception) { }
            }
        }
        LogoutOutcome(serverRevoked, localCleared)
    }

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        val current = session ?: throw AuthFailure.SessionEnded()
        try { return block(current.accessToken) }
        catch (failure: AuthFailure.Http) {
            if (failure.status !in setOf(401, 403)) throw failure
            if (failure.status != 401 || failure.code !in setOf("ACCESS_TOKEN_EXPIRED", "ACCESS_TOKEN_INVALID")) endSession()
        }
        // Once the request might rotate on the server, the previous token must never survive a restart.
        store.clear()
        session = null
        val rotated = try { api.refresh(current) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { endSession() }
        try {
            require(rotated.sessionId == current.sessionId && rotated.account.userId == current.account.userId)
            require(rotated.accessToken != current.accessToken && rotated.refreshToken != current.refreshToken)
            require(withinOriginalSessionExpiry(current.refreshExpiresAt, rotated.refreshExpiresAt))
            store.save(rotated)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            try { api.logout(rotated.refreshToken) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            endSession()
        }
        session = rotated
        try { return block(rotated.accessToken) }
        catch (failure: AuthFailure.Http) {
            if (failure.status in setOf(401, 403)) endSession()
            throw failure
        }
    }

    private suspend fun endSession(): Nothing {
        session = null
        store.clear()
        throw AuthFailure.SessionEnded()
    }
}
