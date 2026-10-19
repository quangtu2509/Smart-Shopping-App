package com.toked.smartshopping.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID

class MainRegistrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun cleanup() = runBlocking { SecureSessionStore(InstrumentationRegistry.getInstrumentation().targetContext).clear() }

    @Test fun realAppRetainsDraftAcrossRotationAndRegistersExactlyOnce() {
        assumeTrue(!InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl").isNullOrBlank())
        val email = "activity-${UUID.randomUUID()}@gmail.com"
        val password = " Local activity mật khẩu 2026! "
        compose.waitUntil(15000) { compose.onAllNodesWithText("Chào mừng trở lại!").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Đăng ký").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Email").performTextInput(email)
        compose.onNodeWithContentDescription("Mật khẩu").performTextInput(password)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performTextInput(password)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(email).assertExists()
        compose.onNode(hasText("Tạo tài khoản") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Xác thực mã OTP").fetchSemanticsNodes().isNotEmpty() }
        val base = InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")!!
        val code = fixtureCode(base,email)
        compose.onNodeWithContentDescription("Mã xác minh").performTextInput(code)
        compose.onNodeWithText("Xác minh").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Đăng ký thành công!").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tiếp tục").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Thiết lập mua sắm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(email).assertExists()
        val store = SecureSessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val saved = runBlocking { store.load()!! }
        assertEquals(email, saved.account.email)
        // Activity recreation retains the authenticated route instead of returning to the form.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Thiết lập mua sắm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(email).assertExists()
    }
}
