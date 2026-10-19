package com.toked.smartshopping.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.auth.*
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun picture(name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = output?.let(::File) ?: compose.activity.getExternalFilesDir("home-evidence")!!
        dir.mkdirs()
        File(dir, name).outputStream().use {
            assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun emptyHomeSearchAndCreateNeverInventPlansOrPrices() {
        compose.setContent { SmartShoppingTheme(darkTheme = false) {
            HomeContent("owner@example.com", 3, "Cân bằng", {}, {}, {})
        } }
        compose.onNodeWithText("Tạo kế hoạch mua sắm").assertIsDisplayed()
        compose.onNodeWithText("Chưa có kế hoạch mua sắm.").assertExists()
        compose.onNodeWithText("500.000₫").assertDoesNotExist()
        picture("home-light.png")
        compose.onNodeWithText("Bắt đầu ngay").performClick()
        compose.onNodeWithText("Tạo kế hoạch mua sắm sẽ có khi tính năng lập kế hoạch được kết nối.").assertIsDisplayed()
        compose.onNodeWithText("Đã hiểu").performClick()
        compose.onNodeWithText("Tìm kế hoạch, mẫu…").performTextInput("cuối tuần")
        compose.onNodeWithText("Không tìm thấy kết quả").assertIsDisplayed()
        compose.onNodeWithContentDescription("Xóa tìm kiếm").performClick()
        compose.onNodeWithText("Tạo kế hoạch mua sắm").assertIsDisplayed()
        compose.onNodeWithText("Mẫu thường dùng").performScrollTo().assertIsDisplayed()
        picture("home-light-bottom.png")
    }

    @Test fun navigationAccountCallbacksAndLogoutConfirmation() {
        var profiles = 0
        var sessions = 0
        var logouts = 0
        compose.setContent { SmartShoppingTheme {
            AuthContent(RegisterUiState(page = AuthPage.HOME_ENTRY,
                account = AccountProfile("owner", "owner@example.com", 2, "CHEAPEST")),
                { _, _ -> }, {}, {}, {}, {}, { profiles++ }, {},
                onSessions = { sessions++ }, onLogout = { logouts++ })
        } }
        compose.onNodeWithText("Bản đồ").performClick()
        compose.onNodeWithText("Bản đồ chưa sẵn sàng").assertIsDisplayed()
        compose.onNodeWithText("Về trang chủ").performClick()
        compose.onNodeWithText("Trang chủ").assertIsSelected()
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithText("Lịch sử chưa sẵn sàng").assertIsDisplayed()
        compose.onNodeWithText("Mẫu").performClick()
        compose.onNodeWithText("Mẫu chưa sẵn sàng").assertIsDisplayed()
        compose.onNodeWithText("Cá nhân").performClick()
        compose.onNodeWithText("owner@example.com").assertIsDisplayed()
        compose.onNodeWithText("Tối đa 2 cửa hàng").assertIsDisplayed()
        compose.onNodeWithText("Hồ sơ mua sắm").performClick()
        compose.onNodeWithText("Phiên đăng nhập").performClick()
        assertEquals(1, profiles); assertEquals(1, sessions)
        compose.onNodeWithText("Đăng xuất").performClick()
        compose.onNodeWithText("Hủy").performClick()
        assertEquals(0, logouts)
        compose.onNodeWithText("Đăng xuất").performClick()
        compose.onNodeWithText("Xác nhận đăng xuất").performClick()
        assertEquals(1, logouts)
    }

    @Test fun darkHomeAndNotifications() {
        compose.setContent { SmartShoppingTheme(darkTheme = true) {
            HomeContent("dark@example.com", 3, "Cân bằng", {}, {}, {})
        } }
        picture("home-dark.png")
        compose.onNodeWithContentDescription("Thông báo").performClick()
        compose.onNodeWithText("Bạn chưa có thông báo mới.").assertIsDisplayed()
    }

    @Test fun largeFontKeepsCreateAndBottomSectionsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SmartShoppingTheme(darkTheme = false) {
                    HomeContent("large@example.com", 3, "Cân bằng", {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Bắt đầu ngay").performScrollTo().assertIsDisplayed()
        picture("home-large-font.png")
        compose.onNodeWithText("Mẫu thường dùng").performScrollTo().assertIsDisplayed()
        picture("home-large-font-bottom.png")
    }
}
