package com.toked.smartshopping.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

class AuthStepUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun picture(name: String) {
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")!!
        val dir = File(path).apply { mkdirs() }
        File(dir,name).outputStream().use { assertTrue(captureFixtureScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun otpResendAndValidationUseVisibleControls() {
        var state by mutableStateOf(RegisterUiState(page=AuthPage.VERIFY_EMAIL,resendSeconds=60))
        var submits = 0; var resends = 0
        compose.setContent { SmartShoppingTheme {
            AuthStepContent(state,{ _,v -> state=state.copy(code=v) },{ submits++ },{}, { resends++ },{})
        } }
        compose.onNodeWithText("Gửi lại mã sau 60s").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mã xác minh").performTextInput("123456")
        compose.onNodeWithText("Xác minh",useUnmergedTree=true).performScrollTo().performClick()
        assertEquals(1,submits); assertEquals("123456",state.code)
        picture("auth-otp.png")
        compose.runOnIdle { state=state.copy(resendSeconds=0) }
        compose.onNodeWithText("Gửi lại mã").performScrollTo().performClick(); assertEquals(1,resends)
    }
    @Test fun successWaitsForContinueAndUsesStitchStyle() {
        var continued = 0
        compose.setContent { SmartShoppingTheme { AuthStepContent(RegisterUiState(page=AuthPage.REGISTER_SUCCESS,
            account=AccountProfile("fixture","fixture@gmail.com",3,"BALANCED")),{ _,_-> },{}, {}, {},{ continued++ }) } }
        compose.onNodeWithText("Đăng ký thành công!").assertExists()
        assertEquals(0,continued)
        picture("auth-success.png")
        compose.onNodeWithText("Tiếp tục").performScrollTo().performClick(); assertEquals(1,continued)
    }
    @Test fun darkResetAndLargeTextRemainScrollable() {
        compose.setContent { SmartShoppingTheme(darkTheme=true) { androidx.compose.material3.Surface {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                AuthStepContent(RegisterUiState(page=AuthPage.RESET_PASSWORD),{ _,_-> },{}, {}, {},{})
            }
        } } }
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().performTextInput("FixtureAb!")
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("Đặt lại mật khẩu").performScrollTo().assertIsDisplayed()
        picture("auth-reset-dark-large.png")
    }
    @Test fun googleAndForgotButtonsDispatchCallbacks() {
        var google=0; var forgot=0
        compose.setContent { SmartShoppingTheme {
            AuthContent(RegisterUiState(page=AuthPage.LOGIN_ENTRY),{ _,_-> },{}, {}, {}, {}, {}, {},
                onGoogle={google++},onForgot={forgot++})
        } }
        compose.onNodeWithText("Quên mật khẩu?").performScrollTo().performClick()
        compose.onNodeWithText("Tiếp tục với Google").performScrollTo().performClick()
        assertEquals(1,google); assertEquals(1,forgot)
    }
    @Test fun liveRecoveryReturnsToLoginAndRevokesExistingSession() {
        val base=InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        assumeTrue(!base.isNullOrBlank())
        val api=AuthApi(base!!,allowLocalHttp=true)
        val email="recovery-${UUID.randomUUID()}@gmail.com"
        val password="ShoppingTestAb!"
        val initial=runBlocking { fixtureRegister(api,base,email,password) }
        val store=SecureSessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { store.clear() }
        val vm=RegisterViewModel(api,store)
        compose.setContent { SmartShoppingTheme { RegisterScreen(vm) } }
        compose.waitUntil(15000) {vm.state.value.page==AuthPage.LOGIN_ENTRY}
        compose.onNodeWithContentDescription("Email").performTextInput(email)
        compose.onNodeWithText("Quên mật khẩu?").performScrollTo().performClick()
        compose.onNodeWithText("Gửi mã xác minh").performScrollTo().performClick()
        compose.waitUntil(15000) {vm.state.value.page==AuthPage.VERIFY_RESET}
        val code=fixtureCode(base,email)
        compose.onNodeWithContentDescription("Mã xác minh").performTextInput(code)
        compose.onNodeWithText("Xác minh").performScrollTo().performClick()
        compose.waitUntil(15000) {vm.state.value.page==AuthPage.RESET_PASSWORD}
        val newPassword="NewShoppingAb!"
        compose.onNodeWithContentDescription("Mật khẩu").performScrollTo().performTextInput(newPassword)
        compose.onNodeWithContentDescription("Xác nhận mật khẩu").performScrollTo().performTextInput(newPassword)
        compose.onNodeWithText("Đặt lại mật khẩu").performScrollTo().performClick()
        compose.waitUntil(15000) {vm.state.value.page==AuthPage.LOGIN_ENTRY}
        assertNull(runBlocking {store.load()})
        try {runBlocking {api.me(initial.accessToken)};fail()} catch(failure:AuthFailure.Http) {assertEquals(401,failure.status)}
        val fresh=runBlocking {api.login(email,newPassword)}
        assertEquals(initial.account.userId,fresh.account.userId)
        runBlocking {api.logout(fresh.refreshToken);store.clear()}
    }
}
