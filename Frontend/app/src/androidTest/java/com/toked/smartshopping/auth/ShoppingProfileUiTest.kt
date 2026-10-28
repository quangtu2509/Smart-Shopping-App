package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
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
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class ShoppingProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun window() { compose.activityRule.scenario.onActivity {
        it.enableEdgeToEdge()
        it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }
    private fun loaded() = ProfileUiState(loading = false, saved = ShoppingProfile(),
        categories = listOf(ProfileReference("food","Thực phẩm")), brands = listOf(ProfileReference("brand","Thương hiệu A")))
    @Composable
    private fun ProfileFixtureContent(state: ProfileUiState, edit: (String,String) -> Unit = { _,_ -> }, select: (String,String,String) -> Unit = { _,_,_ -> },
        save: () -> Unit = {}, leave: () -> Unit = {}, dialog: (String) -> Unit = {}) {
        ShoppingProfileContent(state, edit, select, save, leave, {}, {}, dialog)
    }
    private fun picture(name: String) {
        compose.onNodeWithText("Quay lại").assertIsDisplayed()
        compose.onNodeWithText("Bỏ qua và tiếp tục").assertIsDisplayed()
        compose.waitForIdle()
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = path?.let(::File) ?: compose.activity.getExternalFilesDir("profile-evidence")!!
        directory.mkdirs()
        File(directory,name).outputStream().use { assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun formEditsModesPreferencesAndSave() {
        var state by mutableStateOf(loaded())
        var selected = ""
        var saves = 0
        compose.setContent { SmartShoppingTheme(darkTheme = false) { ProfileFixtureContent(state,
            edit = { field,value -> state = state.copy(draft = when(field) {
                "household_size" -> state.draft.copy(household = value)
                "default_period" -> state.draft.copy(period = value)
                "max_stores" -> state.draft.copy(maxStores = value.toInt())
                "optimization_mode" -> state.draft.copy(mode = value)
                else -> state.draft
            }) }, select = { kind,id,preference -> selected = "$kind:$id:$preference" }, save = { saves++ }) } }
        compose.onNodeWithText("Hồ sơ mặc định").assertExists()
        picture("profile-light-top.png")
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performTextInput("4")
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performImeAction()
        compose.onNodeWithText("Hàng tuần").performScrollTo().performClick()
        compose.onNodeWithText("2 cửa hàng").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Tiết kiệm nhất").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Ưu tiên Thực phẩm").performScrollTo().performClick()
        assertEquals("categories:food:preferred",selected)
        compose.onNodeWithText("Tránh Thương hiệu A").performScrollTo().performClick()
        assertEquals("brands:brand:avoided",selected)
        picture("profile-light-preferences.png")
        compose.onNodeWithText("Lưu hồ sơ").performClick()
        assertEquals(1,saves); assertEquals("4",state.draft.household); assertEquals("1 tuần",state.draft.period)
    }
    @Test fun savingDisablesMutationsAndLeaving() {
        var calls = 0
        compose.setContent { SmartShoppingTheme { ProfileFixtureContent(loaded().copy(saving = true), save = { calls++ }, leave = { calls++ }) } }
        compose.onNodeWithText("Đang lưu…", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Quay lại").assertIsNotEnabled()
        compose.onNodeWithText("Bỏ qua và tiếp tục").assertIsNotEnabled()
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").assertIsNotEnabled()
        assertEquals(0,calls)
    }
    @Test fun conflictAndDiscardDialogsPreserveChoice() {
        var action = ""
        var state by mutableStateOf(loaded().copy(conflict = true, message = "Hồ sơ đã thay đổi trên thiết bị khác."))
        compose.setContent { SmartShoppingTheme { ProfileFixtureContent(state, dialog = { action = it }) } }
        compose.onNodeWithText("Lưu hồ sơ").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(confirmReload = true) }
        compose.onNodeWithText("Tiếp tục chỉnh sửa").performClick(); assertEquals("cancel",action)
        compose.onNodeWithText("Tải bản mới").performClick(); assertEquals("confirmReload",action)
        compose.runOnIdle { state = state.copy(confirmReload = false, confirmLeave = true) }
        compose.onNodeWithText("Bỏ thay đổi").performClick(); assertEquals("discard",action)
    }
    @Test fun darkLargeFontValidationAndEmptyReferences() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)) }
        val state = loaded().copy(categories = emptyList(), brands = emptyList(), draft = ProfileDraft(household = "0"),
            errors = mapOf("household_size" to "Nhập số người nguyên lớn hơn 0 hoặc để trống."))
        compose.setContent { SmartShoppingTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) { ProfileFixtureContent(state) }
        } }
        compose.onNodeWithText("Nhập số người nguyên lớn hơn 0 hoặc để trống.").performScrollTo().assertIsDisplayed()
        picture("profile-dark-large-top.png")
        compose.onNodeWithText("Chưa có dữ liệu nhóm hàng.",substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Chưa có dữ liệu thương hiệu.",substring = true).performScrollTo().assertIsDisplayed()
        picture("profile-dark-large-empty.png")
        compose.onNodeWithText("Lưu hồ sơ").assertIsDisplayed()
    }
    @Test fun loadErrorAndUnknownSaveOutcomeExposeRecovery() {
        var saves = 0
        var state by mutableStateOf(ProfileUiState(loading = false, message = "Chưa tải được hồ sơ."))
        compose.setContent { SmartShoppingTheme { ProfileFixtureContent(state,save = { saves++ }) } }
        compose.onNodeWithText("Thử tải lại").assertIsDisplayed()
        compose.onNodeWithText("Lưu hồ sơ").assertIsNotEnabled()
        compose.runOnIdle { state = loaded().copy(uncertainSave = true, message = "Chưa xác nhận được kết quả lưu.") }
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").assertIsNotEnabled()
        compose.onNodeWithText("Thử lưu lại").performClick(); assertEquals(1,saves)
    }
    @Test fun liveProfileSavesRestoresRefreshesAndResolvesConflict() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        assumeTrue("Requires isolated profile fixture", !base.isNullOrBlank())
        require(base!!.startsWith("http://127.0.0.1:18080/"))
        val api = AuthApi(base,true)
        val email = "profile-${UUID.randomUUID()}@gmail.com"
        val session = fixtureRegister(api,base,email,"Profile device test!")
        val store = object : SessionStore {
            var current: AuthSession? = AuthSession(session.account, "deliberately-invalid-access", session.refreshToken,
                session.sessionId,session.refreshExpiresAt)
            override suspend fun load() = current
            override suspend fun save(session: AuthSession) { current = session }
            override suspend fun clear() { current = null }
        }
        val vm = RegisterViewModel(api,store)
        compose.setContent { SmartShoppingTheme { RegisterScreen(vm) } }
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.HOME_ENTRY }
        assertNotEquals(session.accessToken,store.current!!.accessToken)
        compose.onNodeWithText("Cá nhân").performClick()
        compose.onNodeWithText("Hồ sơ mua sắm").performClick()
        compose.waitUntil(15000) { vm.state.value.profile.saved != null && !vm.state.value.profile.referencesLoading }
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performTextInput("4")
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performImeAction()
        compose.onNodeWithText("Hàng tuần").performScrollTo().performClick()
        compose.onNodeWithText("2 cửa hàng").performScrollTo().performClick()
        compose.onNodeWithText("Tiết kiệm nhất").performScrollTo().performClick()
        compose.onNodeWithText("Ưu tiên Thực phẩm thử nghiệm").performScrollTo().performClick()
        compose.onNodeWithText("Tránh Thương hiệu thử nghiệm").performScrollTo().performClick()
        compose.onNodeWithText("Lưu hồ sơ").performClick()
        compose.waitUntil(15000) { vm.state.value.page == AuthPage.HOME_ENTRY }
        val saved = api.shoppingProfile(store.current!!.accessToken)
        assertEquals(4,saved.householdSize); assertEquals("P1W",saved.defaultPeriod); assertEquals(2,saved.maxStores)
        assertEquals("CHEAPEST",saved.mode); assertEquals(listOf("fixture-food"),saved.preferredCategories)
        assertEquals(listOf("fixture-brand"),saved.avoidedBrands)
        // Reopen the saved profile and emulate an edit from a second device.
        compose.onNodeWithText("Cá nhân").performClick()
        compose.onNodeWithText("Hồ sơ mua sắm").performClick()
        compose.waitUntil(15000) { vm.state.value.profile.saved?.version == saved.version }
        val external = api.saveShoppingProfile(store.current!!.accessToken, ProfileDraft.from(saved).copy(household = "2").patch(saved.version))
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performTextReplacement("5")
        compose.onNodeWithText("Số người trong gia đình (tùy chọn)").performImeAction()
        compose.onNodeWithText("Lưu hồ sơ").performClick()
        compose.waitUntil(15000) { vm.state.value.profile.conflict }
        assertEquals("5",vm.state.value.profile.draft.household)
        compose.onNodeWithText("Tải lại hồ sơ đã lưu").performScrollTo().performClick()
        compose.onNodeWithText("Tải bản mới").performClick()
        compose.waitUntil(15000) { vm.state.value.profile.saved?.version == external.version }
        assertEquals("2",vm.state.value.profile.draft.household)
        api.logout(store.current!!.refreshToken)
    }
}
