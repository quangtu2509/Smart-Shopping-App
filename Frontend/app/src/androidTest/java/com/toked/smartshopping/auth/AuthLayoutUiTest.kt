package com.toked.smartshopping.auth

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Pure form fixtures: never launch MainActivity, access session storage or call an API. */
class AuthLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION") // Match MainActivity's adjustResize window on API 26+.
    @Before fun window() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun form(
        page: AuthPage,
        fontScale: Float = 1f,
        dark: Boolean = false,
        height: Int = 720,
        width: Int = 360,
        state: RegisterUiState = RegisterUiState(page = page),
        onSubmit: () -> Unit = {},
        onSwitch: () -> Unit = {},
        onContinue: () -> Unit = {},
    ) {
        if (dark) compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SmartShoppingTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(width.dp).heightIn(max = height.dp).fillMaxHeight().testTag("auth-viewport")) {
                                if (page == AuthPage.LOGIN_ENTRY) {
                                    LoginContent(state, { _, _ -> }, onSubmit, onSwitch, Modifier.fillMaxSize())
                                } else if (page == AuthPage.REGISTER) {
                                    RegisterContent(state, { _, _ -> }, onSubmit, onSwitch, Modifier.fillMaxSize())
                                } else {
                                    AuthStepContent(state, { _, _ -> }, onSubmit, onSwitch, {}, onContinue, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun action(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun SemanticsNodeInteraction.fullyVisible(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val viewport = compose.onNodeWithTag("auth-viewport").getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("Control $bounds must fit entirely inside $viewport",
            bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        return this
    }

    private fun picture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        val directory = compose.activity.getExternalFilesDir("auth-stitch-evidence")!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            check(captureFixtureScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun nativeLoginDistributesSectionsAndEmphasizesBrand() {
        form(AuthPage.LOGIN_ENTRY, height = 1000, width = 480)
        val logo = compose.onNodeWithContentDescription("Smart Shopping").fullyVisible().getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Email").fullyVisible()
        val submit = action("Đăng nhập").fullyVisible().getUnclippedBoundsInRoot()
        val google = action("Tiếp tục với Google").fullyVisible().getUnclippedBoundsInRoot()
        assertTrue("Logo should be prominent", logo.right - logo.left >= 160.dp)
        assertTrue("Stitch places the password login action before Google", submit.bottom <= google.top)
        compose.onNodeWithText("Phiên được mã hóa trên thiết bị").fullyVisible()
        action("Đăng ký").fullyVisible()
        picture("login-native")
    }

    @Test fun nativeRegistrationShowsBrandAndAllActions() {
        form(AuthPage.REGISTER, height = 1000, width = 480)
        val logo = compose.onNodeWithContentDescription("Smart Shopping").fullyVisible().getUnclippedBoundsInRoot()
        assertTrue("Registration logo should be prominent", logo.right - logo.left >= 120.dp)
        compose.onNode(hasText("Tạo tài khoản") and !hasClickAction()).fullyVisible()
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").fullyVisible()
        action("Tiếp tục với Google").fullyVisible()
        action("Tạo tài khoản").fullyVisible()
        action("Đăng nhập").fullyVisible()
        picture("register-native")
    }

    @Test fun relatedAuthPagesKeepBrandFieldsAndActionsReachable() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.FORGOT))
        compose.setContent {
            SmartShoppingTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("auth-viewport")) {
                        AuthStepContent(state, { _, _ -> }, {}, {}, {}, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        val pages = listOf(
            Triple(AuthPage.FORGOT, "Gửi mã xác minh", "forgot-native"),
            Triple(AuthPage.VERIFY_EMAIL, "Xác minh", "verify-email-native"),
            Triple(AuthPage.VERIFY_RESET, "Xác minh", "verify-reset-native"),
            Triple(AuthPage.RESET_PASSWORD, "Đặt lại mật khẩu", "reset-native"),
            Triple(AuthPage.LINK_GOOGLE, "Xác nhận liên kết", "link-native"),
            Triple(AuthPage.REGISTER_SUCCESS, "Tiếp tục", "success-native"),
        )
        for ((page, button, name) in pages) {
            compose.runOnIdle { state = RegisterUiState(page = page, email = "fixture@gmail.com",
                account = if (page == AuthPage.REGISTER_SUCCESS) AccountProfile("fixture", "fixture@gmail.com", 3, "BALANCED") else null) }
            if (page == AuthPage.FORGOT) {
                compose.onNodeWithContentDescription("Smart Shopping").assertDoesNotExist()
                compose.onNodeWithText("Quên mật khẩu?").performScrollTo().fullyVisible()
            } else if (page != AuthPage.REGISTER_SUCCESS) compose.onNodeWithContentDescription("Smart Shopping").performScrollTo().fullyVisible()
            action(button).performScrollTo().fullyVisible()
            picture(name)
        }
    }

    @Test fun resetSuccessWaitsForExplicitReturnToLogin() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.LOGIN_ENTRY, email = "fixture@gmail.com", passwordResetComplete = true))
        var returns = 0
        compose.setContent { SmartShoppingTheme {
            AuthContent(state, { _, _ -> }, {}, { returns++; state = state.copy(passwordResetComplete = false) }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Đổi mật khẩu thành công!").assertIsDisplayed()
        assertEquals(0, returns)
        picture("reset-success-native")
        action("Đăng nhập ngay").performScrollTo().performClick()
        assertEquals(1, returns)
        compose.onNodeWithText("Chào mừng trở lại!").assertIsDisplayed()
        compose.onNodeWithContentDescription("Email").assertTextContains("fixture@gmail.com")
    }

    @Test fun registrationSuccessDialogKeepsContinueReachableAtLargeFont() {
        var continues = 0
        form(AuthPage.REGISTER_SUCCESS, fontScale = 2f, dark = true, height = 560,
            state = RegisterUiState(page = AuthPage.REGISTER_SUCCESS, account = AccountProfile("fixture", "fixture@gmail.com", 3, "BALANCED")),
            onContinue = { continues++ })
        val title = compose.onNodeWithText("Đăng ký thành công!").performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Dialog must honor the caller's 200% text scale", title.bottom - title.top >= 72.dp)
        action("Tiếp tục").performScrollTo().assertIsDisplayed()
        assertEquals(0, continues)
        picture("register-success-large-dark")
        action("Tiếp tục").performClick()
        assertEquals(1, continues)
    }

    @Test fun forgotEmailEditingAndSubmitUseCurrentValue() {
        var state by mutableStateOf(RegisterUiState(page = AuthPage.FORGOT))
        var submittedEmail: String? = null
        compose.setContent { SmartShoppingTheme {
            AuthStepContent(state, { field, value -> if (field == "email") state = state.copy(email = value) },
                { submittedEmail = state.email }, {}, {}, {}, Modifier.fillMaxSize().safeDrawingPadding().imePadding())
        } }
        compose.onNodeWithContentDescription("Email").performScrollTo().performTextInput("fixture@gmail.com")
        compose.onNodeWithContentDescription("Email").assertTextContains("fixture@gmail.com").performImeAction()
        compose.runOnIdle { assertEquals("fixture@gmail.com", submittedEmail) }
    }

    @Test fun forgotEmailMovesTowardScreenCenterWithoutLogo() {
        form(AuthPage.FORGOT, height = 1000, width = 480)
        compose.onNodeWithContentDescription("Smart Shopping").assertDoesNotExist()
        compose.onNodeWithText("Quên mật khẩu?").fullyVisible()
        val viewport = compose.onNodeWithTag("auth-viewport").getUnclippedBoundsInRoot()
        val email = compose.onNodeWithContentDescription("Email").fullyVisible().getUnclippedBoundsInRoot()
        val fieldCenter = (email.top + email.bottom) / 2f - viewport.top
        val viewportHeight = viewport.bottom - viewport.top
        assertTrue("Email input should sit near the middle of the screen",
            fieldCenter >= viewportHeight * 0.45f && fieldCenter <= viewportHeight * 0.67f)
        action("Gửi mã xác minh").fullyVisible()
        picture("forgot-centered-native")
    }

    @Test fun forgotEmailErrorAndSubmitRemainReachableWithKeyboard() {
        var submits = 0
        form(AuthPage.FORGOT, height = 560, fontScale = 1.5f,
            state = RegisterUiState(page = AuthPage.FORGOT, errors = mapOf("email" to "Nhập email hợp lệ.")),
            onSubmit = { submits++ })
        compose.onNodeWithContentDescription("Email").performScrollTo().performClick()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithText("Nhập email hợp lệ.").performScrollTo().assertIsDisplayed()
        action("Gửi mã xác minh").performScrollTo().fullyVisible()
        picture("forgot-centered-keyboard")
        compose.onNodeWithContentDescription("Email").performScrollTo().performImeAction()
        compose.runOnIdle { assertEquals(1, submits) }
    }

    @Test fun largeFontResetKeepsFieldsAndKeyboardSubmitReachable() {
        var submits = 0
        form(AuthPage.RESET_PASSWORD, fontScale = 2f, dark = true, height = 560,
            onSubmit = { submits++ })
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().performClick()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performImeAction()
        compose.runOnIdle { assertEquals(1, submits) }
        action("Đặt lại mật khẩu").performScrollTo().fullyVisible()
        picture("reset-large-dark")
    }

    @Test fun loginActionsFitPhoneWithoutScrolling() {
        var switches = 0
        form(AuthPage.LOGIN_ENTRY, onSwitch = { switches++ })
        picture("login-phone")
        compose.onNodeWithContentDescription("Email").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mật khẩu").assertIsDisplayed()
        action("Đăng nhập").fullyVisible()
        action("Tiếp tục với Google").fullyVisible()
        action("Đăng ký").fullyVisible().performClick()
        assertEquals(1, switches)
    }

    @Test fun registrationActionsFitPhoneWithoutScrolling() {
        var switches = 0
        form(AuthPage.REGISTER, onSwitch = { switches++ })
        picture("register-phone")
        compose.onNodeWithContentDescription("Email").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mật khẩu").assertIsDisplayed()
        action("Tiếp tục với Google").fullyVisible()
        action("Tạo tài khoản").fullyVisible()
        action("Đăng nhập").fullyVisible().performClick()
        assertEquals(1, switches)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeFontLoginKeepsActionsVisibleAndErrorsReachable() {
        form(AuthPage.LOGIN_ENTRY, fontScale = 2f, dark = true, height = 560,
            state = RegisterUiState(page = AuthPage.LOGIN_ENTRY, errors = mapOf("email" to "Nhập email hợp lệ.")))
        action("Đăng nhập").fullyVisible()
        action("Đăng ký").fullyVisible()
        compose.onNodeWithText("Nhập email hợp lệ.").performScrollTo().assertIsDisplayed()
        action("Tiếp tục với Google").performScrollTo().assertIsDisplayed()
        action("Đăng ký").fullyVisible()
        picture("login-large-dark")
    }

    @Test fun largeFontRegistrationKeepsActionsVisibleAndErrorsReachable() {
        form(AuthPage.REGISTER, fontScale = 2f, height = 560,
            state = RegisterUiState(page = AuthPage.REGISTER,
                errors = mapOf("confirm_password" to "Mật khẩu xác nhận chưa khớp.")))
        action("Tạo tài khoản").fullyVisible()
        action("Đăng nhập").fullyVisible()
        compose.onNodeWithText("Mật khẩu xác nhận chưa khớp.").performScrollTo().assertIsDisplayed()
        action("Đăng nhập").fullyVisible()
        picture("register-large")
    }

    @Test fun keyboardLeavesLoginActionsVisibleAndImeSubmits() {
        var submits = 0
        form(AuthPage.LOGIN_ENTRY, onSubmit = { submits++ })
        compose.onNodeWithContentDescription("Mật khẩu").performScrollTo().performClick()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        action("Đăng nhập").fullyVisible()
        action("Đăng ký").fullyVisible()
        picture("login-keyboard")
        compose.onNodeWithContentDescription("Mật khẩu").performImeAction()
        compose.runOnIdle { assertEquals(1, submits) }
    }

    @Test fun keyboardLeavesRegistrationActionsVisibleAndFieldReachable() {
        form(AuthPage.REGISTER)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().performClick()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().assertIsDisplayed()
        action("Tạo tài khoản").fullyVisible()
        action("Đăng nhập").fullyVisible()
        picture("register-keyboard")
        Espresso.pressBack()
        action("Đăng nhập").fullyVisible()
    }
}
