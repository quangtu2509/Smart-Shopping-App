package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class LoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Fixture(state: RegisterUiState, onEdit: (String, String) -> Unit, onSubmit: () -> Unit, onRegister: () -> Unit) {
        AuthContent(state, onEdit, onSubmit, {}, onRegister, {}, {}, {})
    }

    private fun picture(name: String) {
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = path?.let(::File) ?: compose.activity.getExternalFilesDir("auth02-evidence")!!
        directory.mkdirs()
        File(directory, name).outputStream().use {
            assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun loginValidationToggleAndSubmitUseVisibleControls() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.LOGIN_ENTRY))
        var submits = 0
        var registrations = 0
        compose.setContent {
            SmartShoppingTheme(darkTheme = false) {
                Fixture(state, { field, value -> state = if (field == "email") state.copy(email = value) else state.copy(password = value) },
                    { submits++; state = state.copy(errors = validateLogin(state)) }, { registrations++ })
            }
        }
        picture("login.png")
        compose.onNodeWithText("Đăng nhập").performScrollTo().performClick()
        compose.onNodeWithText("Nhập email hợp lệ.").assertExists()
        compose.onNodeWithContentDescription("Email").performTextInput("fixture@gmail.com")
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput("fixture-password")
        compose.onNodeWithContentDescription("Hiện mật khẩu").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ẩn mật khẩu").assertExists()
        compose.onNodeWithContentDescription("Mật khẩu").performImeAction()
        assertEquals(2, submits)
        compose.onNodeWithText("Đăng ký").performScrollTo().performClick()
        assertEquals(1, registrations)
    }

    @Test fun loadingAndCooldownBlockButtonsAndIme() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.LOGIN_ENTRY, retrySeconds = 20,
            message = "Tài khoản đang bị khóa tạm thời. Vui lòng chờ rồi thử lại."))
        var submits = 0
        compose.setContent { SmartShoppingTheme { Fixture(state, { _, _ -> }, { submits++ }, {}) } }
        compose.onNodeWithText("Thử lại sau 20s").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mật khẩu").performImeAction()
        assertEquals(0, submits)
        picture("login-locked.png")
        compose.runOnIdle { state = state.copy(sending = true) }
        compose.onNodeWithText("Đang đăng nhập…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Email").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Hiện mật khẩu").assertIsNotEnabled()
        compose.onNodeWithText("Đăng ký").performScrollTo().assertIsNotEnabled()
    }

    @Test fun darkLoginAndGenericErrorFixture() {
        compose.setContent {
            SmartShoppingTheme(darkTheme = true) {
                Fixture(RegisterUiState(page = AuthPage.LOGIN_ENTRY, message = "Email hoặc mật khẩu không đúng."), { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("Email hoặc mật khẩu không đúng.").assertExists()
        picture("login-dark.png")
    }

    @Test fun largeFontLoginAndSessionConfirmationRemainReachable() {
        var revoked: String? = null
        var page by mutableStateOf(AuthPage.LOGIN_ENTRY)
        val row = AuthSessionSummary("other", "Thiết bị kiểm thử", "2026-10-16T00:00:00Z", "2026-10-16T00:00:00Z", "2026-11-12T00:00:00Z", false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SmartShoppingTheme(darkTheme = false) {
                    if (page == AuthPage.LOGIN_ENTRY) Fixture(RegisterUiState(page = page), { _, _ -> }, {}, {})
                    else SessionsContent(RegisterUiState(page = page, sessions = listOf(row)), {}, {}, { revoked = it }, {}, {})
                }
            }
        }
        compose.onNodeWithText("Đăng nhập").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Đăng ký").performScrollTo().assertIsDisplayed()
        picture("login-large-font.png")
        compose.runOnIdle { page = AuthPage.SESSIONS }
        compose.onNodeWithText("Thu hồi phiên").performClick()
        assertNull(revoked)
        compose.onNodeWithText("Hủy").performClick(); assertNull(revoked)
        compose.onNodeWithText("Thu hồi phiên").performClick()
        compose.onNodeWithText("Xác nhận thu hồi").performClick(); assertEquals("other", revoked)
    }

    @Test fun signOutMarkerPreventsOldCiphertextRestoringAfterRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureSessionStore(context)
        val file = File(context.noBackupFilesDir, "auth_session.enc")
        val account = AccountProfile("fixture", "fixture@gmail.com", 3, "BALANCED")
        try {
            store.save(AuthSession(account, "old-access", "old-refresh", "session", "2026-11-12T00:00:00Z"))
            val oldBytes = file.readBytes()
            store.clear()
            file.writeBytes(oldBytes)
            assertNull(SecureSessionStore(context).load())
            store.save(AuthSession(account, "new-access", "new-refresh", "session-new", "2026-11-12T00:00:00Z"))
            assertEquals("new-access", SecureSessionStore(context).load()!!.accessToken)
        } finally { store.clear() }
    }

    @Test fun realLoginRestoresRefreshesRevokesAndSwitchesOwners() {
        val base = InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        assumeTrue("Use a separate auth backend and PostgreSQL instance", !base.isNullOrBlank())
        val api = AuthApi(base!!, allowLocalHttp = true)
        val store = SecureSessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val password = " Auth02 device password 2026! "
        val email = "login-${UUID.randomUUID()}@gmail.com"
        val otherEmail = "owner-${UUID.randomUUID()}@gmail.com"
        val registration = runBlocking { store.clear(); fixtureRegister(api,base,email,password) }
        var vm by mutableStateOf(RegisterViewModel(api, store))
        compose.setContent { SmartShoppingTheme { RegisterScreen(vm) } }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.LOGIN_ENTRY }
        compose.onNodeWithContentDescription("Email").performTextInput(email)
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput(password)
        compose.onNodeWithText("Đăng nhập").performScrollTo().performClick()
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.HOME_ENTRY || vm.state.value.message != null }
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
        compose.onNodeWithText("Cá nhân").performClick()
        compose.onNodeWithText(email).assertExists()
        val saved = runBlocking { store.load()!! }
        assertEquals(registration.account.userId, saved.account.userId)
        // A deliberately invalid access token with the valid refresh token exercises real 401 recovery.
        runBlocking { store.save(AuthSession(saved.account, "invalid-access-test", saved.refreshToken, saved.sessionId, saved.refreshExpiresAt)) }
        compose.runOnIdle { vm = RegisterViewModel(api, store) }
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.HOME_ENTRY || vm.state.value.message != null }
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
        val rotated = runBlocking { store.load()!! }
        assertNotEquals(saved.refreshToken, rotated.refreshToken)
        assertEquals(saved.sessionId, rotated.sessionId)
        assertEquals(email, runBlocking { api.me(rotated.accessToken) }.email)
        compose.onNodeWithText("Cá nhân").performClick()
        compose.onNodeWithText("Phiên đăng nhập").performClick()
        compose.waitUntil(15000) { !vm.state.value.sending && vm.state.value.sessions.size >= 2 }
        compose.onNodeWithText("Thu hồi phiên").performScrollTo().performClick()
        compose.onNodeWithText("Xác nhận thu hồi").performClick()
        compose.waitUntil(15000) { !vm.state.value.sending && vm.state.value.sessions.size == 1 }
        assertEquals(rotated.sessionId, runBlocking { api.sessions(rotated.accessToken) }.items.single().id)
        compose.onNodeWithText("Đăng xuất tất cả thiết bị").performScrollTo().performClick()
        compose.onNodeWithText("Xác nhận đăng xuất").performClick()
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.LOGIN_ENTRY }
        assertNull(runBlocking { store.load() })
        try { runBlocking { api.me(rotated.accessToken) }; fail() } catch (failure: AuthFailure.Http) { assertEquals(401, failure.status) }
        val other = runBlocking { fixtureRegister(api,base,otherEmail,password) }
        compose.onNodeWithContentDescription("Email").performTextInput(otherEmail)
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput(password)
        compose.onNodeWithText("Đăng nhập").performScrollTo().performClick()
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.HOME_ENTRY }
        compose.onNodeWithText(otherEmail).assertExists(); compose.onNodeWithText(email).assertDoesNotExist()
        val current = runBlocking { store.load()!! }
        assertEquals(other.account.userId, current.account.userId)
        runBlocking { api.revoke(current.accessToken, current.sessionId); api.logout(other.refreshToken) }
        compose.runOnIdle { vm.onResume() }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.LOGIN_ENTRY }
        assertNull(vm.state.value.account); assertNull(runBlocking { store.load() })
        compose.onNodeWithText(otherEmail).assertDoesNotExist()
    }
}
