package com.toked.smartshopping.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import com.toked.smartshopping.ui.theme.SmartShoppingTheme

@Composable
fun RegisterScreen(viewModel: RegisterViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel, lifecycleOwner, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is AuthUiEvent.Notice -> Toast.makeText(context,event.text,Toast.LENGTH_SHORT).show()
                    AuthUiEvent.ClearGoogle -> try { clearGoogleState(context) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* App logout still clears its own session. */ }
                }
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onResume() }
    BackHandler(enabled = state.page in setOf(AuthPage.REGISTER,AuthPage.FORGOT,AuthPage.VERIFY_EMAIL,AuthPage.VERIFY_RESET,AuthPage.RESET_PASSWORD,AuthPage.LINK_GOOGLE)) { viewModel.showLogin() }
    BackHandler(enabled = state.page == AuthPage.ONBOARDING || state.page == AuthPage.HOME_ENTRY || state.page == AuthPage.REGISTER_SUCCESS) { }
    BackHandler(enabled = state.page == AuthPage.SESSIONS) { viewModel.home() }
    AuthContent(state, viewModel::edit, viewModel::submit, viewModel::showLogin,
        viewModel::showRegister, viewModel::skipOnboarding, viewModel::showProfile, viewModel::retryRestore, modifier,
        onSessions = { viewModel.showSessions() }, onMoreSessions = { viewModel.showSessions(more = true) },
        onRevoke = viewModel::revokeSession, onLogout = viewModel::logout, onHome = viewModel::home,
        onGoogle = { scope.launch { viewModel.signInGoogle { nonce -> googleIdToken(context,nonce) } } },
        onForgot = viewModel::showForgot,onResend = viewModel::resend,onContinue = viewModel::continueAfterRegistration)
}

@Composable
fun AuthContent(
    state: RegisterUiState,
    onEdit: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onSkip: () -> Unit,
    onProfile: () -> Unit,
    onRetryRestore: () -> Unit,
    modifier: Modifier = Modifier,
    onSessions: () -> Unit = {},
    onMoreSessions: () -> Unit = {},
    onRevoke: (String) -> Unit = {},
    onLogout: (Boolean) -> Unit = {},
    onHome: () -> Unit = {},
    onGoogle: () -> Unit = {},
    onForgot: () -> Unit = {},
    onResend: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    if (state.page in setOf(AuthPage.FORGOT,AuthPage.VERIFY_EMAIL,AuthPage.VERIFY_RESET,AuthPage.RESET_PASSWORD,AuthPage.LINK_GOOGLE,AuthPage.REGISTER_SUCCESS)) {
        Surface(modifier.fillMaxSize(),color = MaterialTheme.colorScheme.background) {
            AuthStepContent(state,onEdit,onSubmit,onLogin,onResend,onContinue,Modifier.fillMaxSize().safeDrawingPadding().imePadding())
        }
        return
    }
    if (state.page == AuthPage.LOGIN_ENTRY) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LoginContent(state, onEdit, onSubmit, onRegister,
                Modifier.fillMaxSize().safeDrawingPadding().imePadding(),onGoogle,onForgot)
        }
        return
    }
    if (state.page == AuthPage.REGISTER) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            RegisterContent(
                state, onEdit, onSubmit, onLogin,
                Modifier.fillMaxSize().safeDrawingPadding().imePadding(), onGoogle,
            )
        }
        return
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Smart Shopping", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                when (state.page) {
                    AuthPage.FORGOT,AuthPage.VERIFY_EMAIL,AuthPage.VERIFY_RESET,AuthPage.RESET_PASSWORD,AuthPage.LINK_GOOGLE,AuthPage.REGISTER_SUCCESS -> Unit
                    AuthPage.REGISTER -> Unit
                    AuthPage.STARTING -> {
                        Text("Chào mừng bạn", style = MaterialTheme.typography.headlineMedium)
                        if (state.message == null) CircularProgressIndicator()
                        else {
                            Text(state.message)
                            Button(onRetryRestore, enabled = !state.sending) { Text("Thử lại") }
                            TextButton({ onLogout(false) }, enabled = !state.sending) { Text("Đăng xuất trên thiết bị này") }
                        }
                    }
                    AuthPage.LOGIN_ENTRY -> Unit
                    AuthPage.ONBOARDING -> {
                        Text("Thiết lập mua sắm", style = MaterialTheme.typography.headlineMedium)
                        Text("Tài khoản của bạn đã sẵn sàng.", style = MaterialTheme.typography.bodyLarge)
                        Text(state.account?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Hồ sơ mặc định", style = MaterialTheme.typography.titleMedium)
                                Text("Tối đa ${state.account?.maxStores ?: 3} cửa hàng")
                                Text("Ưu tiên: ${modeLabel(state.account?.mode)}")
                                Text("Bạn có thể bắt đầu với những lựa chọn này.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(onSkip, modifier = Modifier.fillMaxWidth()) { Text("Bỏ qua và tiếp tục") }
                    }
                    AuthPage.HOME_ENTRY -> {
                        Text("Danh sách mua sắm", style = MaterialTheme.typography.headlineMedium)
                        Text(state.account?.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text("Chưa có kế hoạch mua sắm.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onProfile) { Text("Hồ sơ mua sắm") }
                        OutlinedButton(onSessions) { Text("Phiên đăng nhập") }
                        OutlinedButton({ onLogout(false) }) { Text("Đăng xuất") }
                    }
                    AuthPage.SESSIONS -> SessionsContent(state, onSessions, onMoreSessions, onRevoke, onLogout, onHome)
                }
            }
        }
    }
}

private fun modeLabel(mode: String?) = when (mode) {
    "CHEAPEST" -> "Tiết kiệm nhất"
    "CONVENIENT" -> "Thuận tiện nhất"
    else -> "Cân bằng chi phí và sự thuận tiện"
}

@Preview(showBackground = true)
@Composable
private fun RegistrationPreview() {
    SmartShoppingTheme { AuthContent(RegisterUiState(page = AuthPage.REGISTER), { _, _ -> }, {}, {}, {}, {}, {}, {}) }
}
