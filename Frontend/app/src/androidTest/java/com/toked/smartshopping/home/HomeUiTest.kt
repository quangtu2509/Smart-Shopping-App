package com.toked.smartshopping.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.auth.*
import com.toked.smartshopping.shopping.*
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal

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
        var creates = 0
        compose.setContent { SmartShoppingTheme(darkTheme = false) {
            HomeContent("owner@example.com", 3, "Cân bằng", {}, {}, {}, onPlanAction = { if (it == PlanAction.New) creates++ })
        } }
        compose.onNodeWithText("+ Tạo kế hoạch mới").assertIsDisplayed()
        compose.onNodeWithText("Chưa có kế hoạch mua sắm.", substring = true).assertExists()
        compose.onNodeWithText("500.000₫").assertDoesNotExist()
        compose.onNodeWithText("Giá tốt hôm nay").assertDoesNotExist()
        picture("home-light.png")
        compose.onNodeWithText("+ Tạo kế hoạch mới").performClick()
        assertEquals(1, creates)
        compose.onNodeWithText("Mẫu thường dùng").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lịch sử gần đây").performScrollTo().assertIsDisplayed()
        picture("home-light-bottom.png")
        compose.onNodeWithText("Dùng lại mẫu").performScrollTo().performClick()
        compose.onNodeWithText("Mẫu chưa sẵn sàng").assertIsDisplayed()
    }

    @Test fun planCardsShowStatusAndNameMissingPricesInsteadOfZero() {
        val opened = mutableListOf<PlanAction>()
        fun money(v: String) = Money("VND", BigDecimal(v))
        fun plan(id: String, status: PlanState, selected: String, missing: List<String>) = ShoppingPlan(id, 1, 0, status, money("500000"),
            listOf(Selection("r1", null)), listOf("c1"), 3,
            BudgetSummary(money("500000"), money(selected), money("500000"), missing.isEmpty(), missing, money("0"), emptyList()),
            "READY", "2026-11-12T00:00:00Z", "2026-11-12T00:00:00Z")
        val plans = PlanUiState(plans = listOf(plan("draft", PlanState.DRAFT, "0", emptyList()),
            plan("final", PlanState.FINALIZED, "245000", listOf("i1"))))
        compose.setContent { SmartShoppingTheme(darkTheme = false) {
            HomeContent("owner@example.com", 3, "Cân bằng", {}, {}, {}, plans = plans, onPlanAction = { opened += it })
        } }
        compose.onNodeWithText("Bản nháp").assertIsDisplayed()
        compose.onNodeWithText("Tiếp tục chọn hàng ›").assertIsDisplayed()
        compose.onNodeWithText("Thiếu giá 1 món").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Đã chọn 245.000 ₫").assertIsDisplayed()
        // A draft with nothing priced yet must not claim a 0 ₫ total.
        compose.onNodeWithText("Đã chọn 0 ₫").assertDoesNotExist()
        compose.onNodeWithText("Xem kế hoạch ›").performScrollTo().performClick()
        assertEquals(PlanAction.Open("final"), opened.last())
        picture("home-plans.png")
    }

    @Test fun deleteOnlyOnUnfinalizedPlansAndAsksBeforeDeleting() {
        val actions = mutableListOf<PlanAction>()
        val zero = Money("VND", BigDecimal.ZERO); val budget = Money("VND", BigDecimal("300000"))
        fun plan(id: String, revision: Int, status: PlanState) = ShoppingPlan(id, 2, revision, status, budget, listOf(Selection("r1", null)), listOf("c1"), 3,
            BudgetSummary(budget, zero, budget, true, emptyList(), zero, emptyList()), "READY", "2026-11-11T00:00:00Z", "2026-11-11T00:00:00Z")
        val draft = plan("draft", 0, PlanState.BROWSING_PRODUCTS)
        var state by mutableStateOf(PlanUiState(plans = listOf(draft, plan("final", 1, PlanState.FINALIZED))))
        compose.setContent { SmartShoppingTheme(darkTheme = false) {
            HomeContent("owner@example.com", 3, "Cân bằng", {}, {}, {}, plans = state, onPlanAction = { actions += it })
        } }
        compose.onAllNodesWithText("Xóa").assertCountEquals(1)
        compose.onNodeWithText("Xóa").performClick()
        assertEquals(PlanAction.Delete("draft"), actions.last())
        state = state.copy(confirmDelete = draft)
        compose.onNodeWithText("Xóa kế hoạch?").assertIsDisplayed()
        picture("home-delete-confirm.png")
        compose.onNodeWithText("Hủy").performClick(); assertEquals(PlanAction.CancelDelete, actions.last())
        compose.onNodeWithText("Xóa kế hoạch").performClick(); assertEquals(PlanAction.ConfirmDelete, actions.last())
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
        compose.onNodeWithText("+ Tạo kế hoạch mới").performScrollTo().assertIsDisplayed()
        picture("home-large-font.png")
        compose.onNodeWithText("Mẫu thường dùng").performScrollTo().assertIsDisplayed()
        picture("home-large-font-bottom.png")
    }
}
