package com.toked.smartshopping.shopping

import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.BuildConfig
import com.toked.smartshopping.auth.*
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PlanUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("DEPRECATION")
    @Before fun window() { compose.activityRule.scenario.onActivity {
        it.enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }
    private fun fixture(step: PlanStep) = PlanUiState(step = step, maxStores = 3,
        draft = PlanDraft("500000", selections = listOf(Selection("r-a", null), Selection("r-b", null)), categoryIds = listOf("food")),
        retailers = listOf("A", "B", "C", "D").map { PlanReference("r-${it.lowercase()}", "Cửa hàng $it", "ACTIVE") },
        categories = listOf(PlanReference("food", "Rau củ & thực phẩm", "ACTIVE"), PlanReference("home", "Đồ dùng gia đình", "ACTIVE"),
            PlanReference("milk", "Sữa & đồ uống", "ACTIVE"), PlanReference("care", "Chăm sóc cá nhân", "ACTIVE")))

    @Composable private fun Frame(state: PlanUiState, onAction: (PlanAction) -> Unit, font: Float = 1f, dark: Boolean = false, height: Int = 792) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
            SmartShoppingTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(360.dp).heightIn(max = height.dp).fillMaxHeight().testTag("plan-viewport")) {
                        PlanContent(state, onAction, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun SemanticsNodeInteraction.fullyVisible(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val bounds = getUnclippedBoundsInRoot(); val viewport = compose.onNodeWithTag("plan-viewport").getUnclippedBoundsInRoot()
        assertTrue("Action must remain inside viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right)
        return this
    }
    private fun picture(name: String) {
        compose.waitForIdle()
        // Native ripple rendering can continue after Compose becomes idle.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        val dir = compose.activity.getExternalFilesDir("plan01-evidence")!!; dir.mkdirs()
        File(dir, "$name.png").outputStream().use { check(captureFixtureScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun budgetPresetsInputImeAndFooterWorkOnPhoneViewport() {
        var state by mutableStateOf(fixture(PlanStep.BUDGET).copy(draft = PlanDraft()))
        var continues = 0
        compose.setContent { Frame(state, { action -> when (action) {
            is PlanAction.Budget -> state = state.copy(draft = state.draft.copy(budget = action.value))
            PlanAction.Continue -> continues++
            else -> Unit
        } }) }
        button("Tiếp tục").fullyVisible()
        picture("budget-light")
        compose.onNodeWithText("500.000 ₫").performScrollTo().performClick()
        assertEquals("500000", state.draft.budget)
        compose.onNodeWithContentDescription("Ngân sách dự kiến").performScrollTo().performClick().performTextReplacement("750000")
        compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        button("Tiếp tục").fullyVisible()
        picture("budget-keyboard")
        compose.onNodeWithContentDescription("Ngân sách dự kiến").performImeAction()
        assertEquals(1, continues)
        compose.runOnIdle { state = state.copy(draft = state.draft.copy(budget = "0"), errors = mapOf("budget" to "Ngân sách phải lớn hơn 0.")) }
        compose.onNodeWithText("Ngân sách phải lớn hơn 0.").fullyVisible()
        button("Tiếp tục").fullyVisible()
        picture("budget-error-keyboard")
        Espresso.pressBack()
    }

    @Test fun storeSelectionAndBranchCallbackAreDistinctAndFourthChoiceErrorIsVisible() {
        val events = mutableListOf<PlanAction>()
        var state by mutableStateOf(fixture(PlanStep.STORES))
        compose.setContent { Frame(state, { events += it }) }
        button("Tiếp tục").fullyVisible()
        compose.onNodeWithText("Cửa hàng A").performScrollTo().assertIsDisplayed()
        picture("stores-light")
        compose.onNodeWithText("Cửa hàng C").performScrollTo().performClick()
        assertEquals(PlanAction.Retailer("r-c"), events.last())
        compose.onAllNodesWithText("Chọn chi nhánh")[0].performScrollTo().performClick()
        assertEquals(PlanAction.Branches("r-a"), events.last())
        compose.runOnIdle { state = state.copy(draft = state.draft.copy(selections = state.draft.selections + Selection("r-c", null)),
            errors = mapOf("selections" to "Chỉ chọn tối đa 3 nơi mua. Bỏ bớt một lựa chọn trước.")) }
        compose.onNodeWithText("Chỉ chọn tối đa 3 nơi mua.", substring = true).fullyVisible()
        button("Tiếp tục").fullyVisible()
        compose.runOnIdle { state = state.copy(branchesOpen = true, branches = listOf(PlanStore("branch-d", "r-d", "Chi nhánh D", "Khu vực thử nghiệm", false))) }
        compose.onNodeWithText("Chỉ chọn tối đa 3 nơi mua.", substring = true).assertIsDisplayed()
        picture("branch-limit-error")
    }

    @Test fun categoriesKeepSummaryMultiSelectionAndSaveAccessible() {
        var state by mutableStateOf(fixture(PlanStep.CATEGORIES))
        var saves = 0
        compose.setContent { Frame(state, { action -> when (action) {
            is PlanAction.Category -> state = state.copy(draft = state.draft.copy(categoryIds = state.draft.categoryIds + action.id))
            PlanAction.Continue -> saves++
            else -> Unit
        } }) }
        button("Lưu kế hoạch").fullyVisible()
        compose.onNodeWithText("Cửa hàng A · Cả chuỗi").assertExists()
        picture("categories-light")
        compose.onNodeWithText("Đồ dùng gia đình").performScrollTo().performClick()
        assertTrue("home" in state.draft.categoryIds)
        button("Lưu kế hoạch").performClick(); assertEquals(1, saves)
    }

    @Test fun loadingEmptyAndReferenceRetryNeverInventRetailers() {
        var state by mutableStateOf(PlanUiState(step = PlanStep.STORES, referencesLoading = true))
        var retries = 0
        compose.setContent { Frame(state, { if (it == PlanAction.RetryReferences) retries++ }) }
        compose.onNodeWithText("Đang tải dữ liệu…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = state.copy(referencesLoading = false, maxStores = 3) }
        compose.onNodeWithText("Chưa có chuỗi cửa hàng khả dụng.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bách Hóa Xanh").assertDoesNotExist()
        picture("stores-empty")
        compose.runOnIdle { state = state.copy(referenceError = "Chưa tải được dữ liệu.") }
        button("Tải lại dữ liệu").performScrollTo().performClick(); assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(step = PlanStep.CATEGORIES, referenceError = null) }
        compose.onNodeWithText("Chưa có nhóm hàng khả dụng.", substring = true).performScrollTo().assertIsDisplayed()
        button("Lưu kế hoạch").fullyVisible()
    }

    @Test fun pendingAndSavingDisableEditsButKeepExplicitRetry() {
        var state by mutableStateOf(fixture(PlanStep.CATEGORIES).copy(pending = PlanMutation("fixture-key", "{}"), message = "Chưa xác nhận kết quả lưu."))
        var retries = 0
        compose.setContent { Frame(state, { if (it == PlanAction.Save) retries++ }) }
        button("Thử lưu lại").fullyVisible().performClick(); assertEquals(1, retries)
        compose.onNodeWithText("Rau củ & thực phẩm").assertIsNotEnabled()
        picture("save-retry")
        compose.runOnIdle { state = state.copy(saving = true) }
        button("Đang lưu…").fullyVisible().assertIsNotEnabled()
        compose.onAllNodes(hasClickAction()).assertAll(isNotEnabled())
    }

    @Test fun conflictAndDiscardRequireExplicitChoice() {
        var state by mutableStateOf(fixture(PlanStep.CATEGORIES).copy(conflict = true))
        val events = mutableListOf<PlanAction>()
        compose.setContent { Frame(state, { events += it }) }
        picture("version-conflict")
        button("Giữ thông tin đang nhập").performClick(); assertEquals(PlanAction.CancelConflict, events.last())
        compose.runOnIdle { state = state.copy(conflict = false, confirmExit = true) }
        button("Tiếp tục chỉnh sửa").performClick(); assertEquals(PlanAction.CancelExit, events.last())
        assertFalse(PlanAction.ConfirmExit in events)
    }

    @Test fun largeFontDarkCategoriesAndErrorsScrollWithoutHidingSave() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT)) }
        val initial = fixture(PlanStep.CATEGORIES)
        val state = initial.copy(draft = initial.draft.copy(categoryIds = emptyList()), errors = mapOf("category_ids" to "Chọn ít nhất một nhóm hàng."))
        compose.setContent { Frame(state, {}, font = 2f, dark = true, height = 700) }
        button("Lưu kế hoạch").fullyVisible()
        compose.onNodeWithText("Chọn ít nhất một nhóm hàng.").fullyVisible()
        compose.onNodeWithText("Chăm sóc cá nhân").performScrollTo().assertIsDisplayed()
        button("Lưu kế hoạch").fullyVisible()
        picture("categories-large-dark")
    }

    private class Store : SessionStore {
        var current: AuthSession? = null
        override suspend fun save(session: AuthSession) { current = session }
        override suspend fun load() = current
        override suspend fun clear() { current = null }
    }
    @Test fun liveDraftSurvivesRotationSaveReadbackEditAndConflict() {
        val base = InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        require(base == "http://127.0.0.1:18080/api/v1/" && BuildConfig.AUTH_API_BASE_URL == base) { "Use only the isolated fixture at 18080" }
        val api = AuthApi(base, allowLocalHttp = true); val store = Store()
        runBlocking { store.save(fixtureRegister(api, base, "plan-device-${UUID.randomUUID()}@gmail.com", "Device plan password!")) }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = RegisterViewModel(api, store) as T
        }
        lateinit var vm: RegisterViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it, factory)[RegisterViewModel::class.java] }
        compose.setContent { SmartShoppingTheme(darkTheme = false) { RegisterScreen(vm) } }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.HOME_ENTRY }
        button("Bắt đầu ngay").performScrollTo().performClick()
        compose.waitUntil(15000) { !vm.plans.state.value.referencesLoading && vm.plans.state.value.maxStores != null }
        compose.onNodeWithContentDescription("Ngân sách dự kiến").performTextInput("500000")
        button("Tiếp tục").performClick()
        compose.onNodeWithText("Cửa hàng thử nghiệm A").performScrollTo().performClick()
        compose.onAllNodesWithText("Chọn chi nhánh")[0].performScrollTo().performClick()
        compose.waitUntil(15000) { !vm.plans.state.value.branchesLoading && vm.plans.state.value.branches.isNotEmpty() }
        compose.onNodeWithText("Chi nhánh thử nghiệm A").performScrollTo().performClick()
        assertEquals("plan-branch-a", vm.plans.state.value.draft.selections.single().storeId)
        picture("branch-live")
        button("Xong").performClick()
        compose.onNodeWithText("Cửa hàng thử nghiệm B").performScrollTo().performClick()
        button("Tiếp tục").performClick()
        compose.onNodeWithText("Thực phẩm cho kế hoạch").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            val restored = ViewModelProvider(it, factory)[RegisterViewModel::class.java]
            assertSame(vm, restored)
            it.enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
            it.setContent { SmartShoppingTheme(darkTheme = false) { RegisterScreen(restored) } }
        }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.HOME_ENTRY && vm.plans.state.value.step == PlanStep.CATEGORIES }
        assertEquals("500000", vm.plans.state.value.draft.budget)
        assertTrue("plan-food" in vm.plans.state.value.draft.categoryIds)
        button("Lưu kế hoạch").performClick()
        compose.waitUntil(15000) { vm.plans.state.value.step == PlanStep.DETAIL && vm.plans.state.value.saved != null }
        val saved = vm.plans.state.value.saved!!
        val access = store.current!!.accessToken
        assertEquals(saved, runBlocking { api.plan(access, saved.plan.id) })
        assertEquals(1, saved.plan.version); assertEquals(PlanState.DRAFT, saved.plan.status)
        assertEquals(2, saved.plan.selections.size)
        picture("draft-live-saved")
        button("Về trang chủ").performClick()
        compose.waitUntil(15000) { !vm.plans.state.value.plansLoading && vm.plans.state.value.plans.any { it.id == saved.plan.id } }
        compose.onNodeWithText(planTitle(saved.plan)).performScrollTo().performClick()
        compose.waitUntil(15000) { vm.plans.state.value.saved != null && !vm.plans.state.value.opening }
        button("Chỉnh sửa kế hoạch").performClick()
        compose.onNodeWithContentDescription("Ngân sách dự kiến").performTextReplacement("600000")
        button("Tiếp tục").performClick(); button("Tiếp tục").performClick()
        val draft = PlanDraft.from(saved.plan).copy(budget = "750000")
        val otherDevice = runBlocking { api.savePlan(access, PlanMutation(UUID.randomUUID().toString(), draft.body(saved.plan.version), saved.plan.id)) }
        button("Lưu thay đổi").performClick()
        compose.waitUntil(15000) { vm.plans.state.value.conflict }
        assertEquals("600000", vm.plans.state.value.draft.budget)
        button("Tải bản mới").performClick()
        compose.waitUntil(15000) { vm.plans.state.value.saved?.plan?.version == otherDevice.plan.version }
        assertEquals("750000", vm.plans.state.value.draft.budget)
        button("Chỉnh sửa kế hoạch").performClick()
        compose.onNodeWithContentDescription("Ngân sách dự kiến").performTextReplacement("800000")
        button("Tiếp tục").performClick(); button("Tiếp tục").performClick(); button("Lưu thay đổi").performClick()
        compose.waitUntil(15000) { vm.plans.state.value.saved?.plan?.version == otherDevice.plan.version + 1 }
        assertEquals("800000", runBlocking { api.plan(access, saved.plan.id) }.plan.budget.amount.toPlainString())
        picture("draft-live-edited")
        assertEquals(1, runBlocking { api.plans(access, null) }.items.size)
        compose.runOnIdle { vm.plans.clear() }
        runBlocking { api.logout(store.current!!.refreshToken) }
    }
}
