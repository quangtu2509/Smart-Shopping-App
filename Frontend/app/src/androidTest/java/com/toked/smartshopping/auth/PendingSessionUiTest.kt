package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PendingSessionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loginKeepsStorageRetryButDisablesOtherAuthRoutes() = fixture(AuthPage.LOGIN_ENTRY)
    @Test fun registrationKeepsStorageRetryButDisablesLoginAndBack() = fixture(AuthPage.REGISTER)
    @Test fun verifiedOtpKeepsStorageRetryButDisablesRestartAndResend() = fixture(AuthPage.VERIFY_EMAIL)
    @Test fun linkedGoogleKeepsStorageRetryButDisablesBack() = fixture(AuthPage.LINK_GOOGLE)

    private fun fixture(page: AuthPage) {
        var state by mutableStateOf(RegisterUiState(page = page, savingSession = true,
            email = "fixture@gmail.com", message = "Đã xác thực nhưng chưa lưu được phiên. Hãy thử lưu lại."))
        var routes = 0
        var submits = 0
        compose.setContent {
            SmartShoppingTheme {
                AuthContent(state, { _, _ -> }, { submits++; state = state.copy(sending = true) },
                    { routes++ }, { routes++ }, {}, {}, {},
                    onGoogle = { routes++ }, onForgot = { routes++ }, onResend = { routes++ })
            }
        }
        fun assertRoutesDisabled() {
            when (page) {
                AuthPage.LOGIN_ENTRY -> {
                    compose.onNodeWithText("Đăng ký").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithText("Quên mật khẩu?").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithText("Tiếp tục với Google").performScrollTo().assertIsNotEnabled()
                }
                AuthPage.REGISTER -> {
                    compose.onNodeWithText("Đăng nhập").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithContentDescription("Quay lại đăng nhập").assertIsNotEnabled()
                    compose.onNodeWithText("Tiếp tục với Google").performScrollTo().assertIsNotEnabled()
                }
                AuthPage.VERIFY_EMAIL -> {
                    compose.onNodeWithContentDescription("Quay lại đăng nhập").assertIsNotEnabled()
                    compose.onNodeWithText("Đổi email hoặc bắt đầu lại").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithText("Gửi lại mã").performScrollTo().assertIsNotEnabled()
                }
                AuthPage.LINK_GOOGLE -> compose.onNodeWithContentDescription("Quay lại đăng nhập").assertIsNotEnabled()
                else -> error("Unsupported fixture page")
            }
        }
        assertRoutesDisabled()
        compose.onNodeWithContentDescription(if (page == AuthPage.VERIFY_EMAIL) "Mã xác minh" else if (page == AuthPage.LINK_GOOGLE) "Mật khẩu" else "Email")
            .assertIsNotEnabled()
        compose.onNodeWithText("Lưu lại phiên đăng nhập").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submits)
        assertRoutesDisabled()
        // While the local write runs, the same button must not start a second write.
        compose.onAllNodes(hasClickAction()).assertAll(isNotEnabled())
        assertEquals(0, routes)
    }
}
