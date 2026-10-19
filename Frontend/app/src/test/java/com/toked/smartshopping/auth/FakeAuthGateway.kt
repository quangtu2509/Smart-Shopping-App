package com.toked.smartshopping.auth

internal open class FakeAuthGateway : AuthGateway {
    override suspend fun register(email: String, password: String, confirmation: String): AuthSession = error("Unexpected register")
    override suspend fun me(accessToken: String): AccountProfile = error("Unexpected me")
    override suspend fun login(email: String, password: String): AuthSession = error("Unexpected login")
    override suspend fun refresh(session: AuthSession): AuthSession = error("Unexpected refresh")
    override suspend fun logout(refreshToken: String) { error("Unexpected logout") }
    override suspend fun sessions(accessToken: String, cursor: String?): AuthSessionPage = error("Unexpected sessions")
    override suspend fun revoke(accessToken: String, sessionId: String) { error("Unexpected revoke") }
    override suspend fun logoutAll(accessToken: String) { error("Unexpected logout-all") }
}
