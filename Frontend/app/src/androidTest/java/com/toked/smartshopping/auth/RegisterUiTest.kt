package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.io.File

class RegisterUiTest {
    private fun outputDirectory(): File = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.let(::File) ?: compose.activity.getExternalFilesDir("auth01-evidence")!!
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val password = " Local device mật khẩu test 2026! "

    private fun content(state: RegisterUiState, onEdit: (String, String) -> Unit = { _, _ -> }, onSubmit: () -> Unit = {}) {
        compose.setContent { SmartShoppingTheme { AuthContent(state, onEdit, onSubmit, {}, {}, {}, {}, {}) } }
    }

    @Test fun validationAndLoadingSemantics() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.REGISTER))
        compose.setContent {
            SmartShoppingTheme {
                AuthContent(state, { field, value -> state = when (field) {
                    "email" -> state.copy(email = value)
                    "password" -> state.copy(password = value)
                    else -> state.copy(confirmation = value)
                } }, { state = state.copy(errors = validate(state)) }, {}, {}, {}, {}, {})
            }
        }
        val directory = outputDirectory()
        directory.mkdirs()
        File(directory, "registration.png").outputStream().use {
            assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNode(hasText("Tạo tài khoản") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Dùng địa chỉ Gmail của bạn (@gmail.com).").assertExists()
        compose.onNodeWithContentDescription("Email").performTextInput("shopper@gmail.com")
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput(password)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performTextInput(password)
        compose.runOnIdle { state = state.copy(sending = true, errors = emptyMap()) }
        compose.onNodeWithText("Đang gửi…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Email").assertIsNotEnabled()
    }

    @Test fun duplicateErrorAndOnboardingEntry() {
        content(RegisterUiState(page = AuthPage.REGISTER, errors = mapOf("email" to "Email đã được sử dụng. Đăng nhập hoặc dùng email khác.")))
        compose.onNodeWithText("Email đã được sử dụng. Đăng nhập hoặc dùng email khác.").assertExists()
    }

    @Test fun passwordVisibilityIsIndependentAndLoginActionsWork() {
        var loginClicks = 0
        compose.setContent {
            SmartShoppingTheme(darkTheme = false) {
                AuthContent(RegisterUiState(page = AuthPage.REGISTER, password = "fixture-password", confirmation = "fixture-password"),
                    { _, _ -> }, {}, { loginClicks++ }, {}, {}, {}, {})
            }
        }
        val masked = SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)
        val hidden = "•".repeat("fixture-password".length)
        compose.onNodeWithContentDescription("Mật khẩu").assert(masked)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").assert(masked)
        assertRenderedField("Mật khẩu", hidden)
        assertRenderedField("Xác nhận mật khẩu", hidden)
        compose.onNodeWithContentDescription("Hiện mật khẩu").performScrollTo().performClick()
        assertRenderedField("Mật khẩu", "fixture-password")
        assertRenderedField("Xác nhận mật khẩu", hidden)
        compose.onNodeWithContentDescription("Hiện mật khẩu xác nhận").performScrollTo().performClick()
        assertRenderedField("Xác nhận mật khẩu", "fixture-password")
        compose.onNodeWithContentDescription("Ẩn mật khẩu").performScrollTo().performClick()
        assertRenderedField("Mật khẩu", hidden)
        compose.onNodeWithText("Đăng nhập").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Quay lại đăng nhập").performClick()
        assertEquals(2, loginClicks)
    }

    private fun assertRenderedField(label: String, expected: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(action(layouts))
            assertEquals(expected, layouts.single().layoutInput.text.text)
        }
    }

    @Test fun cooldownAndSendingDisableSubmitIncludingIme() {
        var submits = 0
        var state by mutableStateOf(RegisterUiState(page = AuthPage.REGISTER, retrySeconds = 12))
        compose.setContent {
            SmartShoppingTheme {
                AuthContent(state, { _, _ -> }, { submits++ }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Thử lại sau 12s").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().performImeAction()
        assertEquals(0, submits)
        compose.runOnIdle { state = state.copy(sending = true) }
        compose.onNodeWithText("Đang gửi…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Đăng nhập").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Quay lại đăng nhập").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Hiện mật khẩu").assertIsNotEnabled()
        assertEquals(0, submits)
    }

    @Test fun darkRegistrationFixture() {
        compose.setContent {
            SmartShoppingTheme(darkTheme = true) {
                AuthContent(RegisterUiState(page = AuthPage.REGISTER), { _, _ -> }, {}, {}, {}, {}, {}, {})
            }
        }
        saveScreenshot("registration-dark.png")
        compose.onNode(hasText("Tạo tài khoản") and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Đăng nhập").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeFontRegistrationScrollsToActionsAndErrors() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SmartShoppingTheme(darkTheme = false) {
                    AuthContent(RegisterUiState(page = AuthPage.REGISTER, errors = mapOf("email" to "Email đã được sử dụng. Đăng nhập hoặc dùng email khác.")),
                        { _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Email đã được sử dụng. Đăng nhập hoặc dùng email khác.").performScrollTo().assertIsDisplayed()
        saveScreenshot("registration-large-font.png")
        compose.onNode(hasText("Tạo tài khoản") and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Đăng nhập").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phiên được mã hóa trên thiết bị").performScrollTo().assertIsDisplayed()
        saveScreenshot("registration-large-font-actions.png")
    }

    private fun saveScreenshot(name: String) {
        val directory = outputDirectory()
        directory.mkdirs()
        File(directory, name).outputStream().use {
            assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun onboardingUsesDefaultsAndSkipsToHome() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.ONBOARDING,
            account = AccountProfile("fixture", "fixture@gmail.com", 3, "BALANCED")))
        compose.setContent { SmartShoppingTheme { AuthContent(state, { _, _ -> }, {}, {}, {},
            { state = state.copy(page = AuthPage.HOME_ENTRY) }, {}, {}) } }
        compose.onNodeWithText("Thiết lập mua sắm").assertExists()
        compose.onNodeWithText("Tối đa 3 cửa hàng").assertExists()
        val directory = outputDirectory()
        directory.mkdirs()
        File(directory, "onboarding.png").outputStream().use {
            assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("Bỏ qua và tiếp tục").performClick()
        compose.onNodeWithText("Tạo kế hoạch mua sắm").assertExists()
    }

    @Test fun encryptedStorageRoundTripAndTamperingFailsClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureSessionStore(context)
        store.clear()
        val session = AuthSession(AccountProfile("fixture", "fixture@gmail.com", 3, "BALANCED"),
            "fixture-access", "fixture-refresh", "fixture-session", "2026-11-12T00:00:00Z")
        try {
            store.save(session)
            val loaded = SecureSessionStore(context).load()!!
            assertEquals(session.refreshToken, loaded.refreshToken)
            val file = File(context.noBackupFilesDir, "auth_session.enc")
            val encrypted = file.readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("fixture-refresh"))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            try { store.load(); fail("Tampered session must not authenticate") }
            catch (_: javax.crypto.AEADBadTagException) { }
        } finally { store.clear() }
    }

    @Test fun realRegistrationNavigatesAfterSecureSaveAndMeMatchesOwner() {
        val base = InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        assumeTrue("Supply an isolated backend URL for live registration test", !base.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureSessionStore(context)
        runBlocking { store.clear() }
        val api = AuthApi(base!!, allowLocalHttp = true)
        val vm = RegisterViewModel(api, store)
        val email = "android-${UUID.randomUUID()}@gmail.com"
        compose.setContent { SmartShoppingTheme { RegisterScreen(vm) } }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.LOGIN_ENTRY }
        compose.onNodeWithText("Đăng ký").performScrollTo().performClick()
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.REGISTER }
        compose.onNodeWithContentDescription("Email").performTextInput(email)
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput(password)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performTextInput(password)
        compose.onNode(hasText("Tạo tài khoản") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.VERIFY_EMAIL || vm.state.value.message != null }
        assertEquals(AuthPage.VERIFY_EMAIL,vm.state.value.page)
        val code = fixtureCode(base,email)
        compose.onNodeWithContentDescription("Mã xác minh").performTextInput(code)
        compose.onNodeWithText("Xác minh").performScrollTo().performClick()
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.REGISTER_SUCCESS }
        compose.onNodeWithText("Tiếp tục").performScrollTo().performClick()
        compose.waitUntil(30000) { vm.state.value.page == AuthPage.ONBOARDING || vm.state.value.message != null }
        assertEquals("Live registration did not reach onboarding", AuthPage.ONBOARDING, vm.state.value.page)
        compose.onNodeWithText("Thiết lập mua sắm").assertExists()
        compose.onNodeWithText(email).assertExists()
        compose.onNodeWithText("Tối đa 3 cửa hàng").assertExists()
        val saved = runBlocking { store.load()!! }
        val confirmed = runBlocking { api.me(saved.accessToken) }
        assertEquals(email, confirmed.email); assertEquals(saved.account.userId, confirmed.userId)
        val restored = RegisterViewModel(api, SecureSessionStore(context))
        restored.start()
        compose.waitUntil(15000) { restored.state.value.page == AuthPage.HOME_ENTRY }
        assertEquals(email, restored.state.value.account!!.email)
        assertEquals("", vm.state.value.password)
        compose.onNodeWithText("Bỏ qua và tiếp tục").performClick()
        compose.onNodeWithText("Tạo kế hoạch mua sắm").assertExists()
        runBlocking { store.clear() }
    }
}
