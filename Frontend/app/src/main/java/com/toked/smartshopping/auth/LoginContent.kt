package com.toked.smartshopping.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R
import com.toked.smartshopping.ui.theme.SmartShoppingTheme

@Composable
fun LoginContent(state: RegisterUiState, onEdit: (String, String) -> Unit, onSubmit: () -> Unit,
    onRegister: () -> Unit, modifier: Modifier = Modifier, onGoogle: () -> Unit = {}, onForgot: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val fieldsEnabled = !state.sending && !state.savingSession
    val submit = {
        if (!state.sending && state.retrySeconds == 0) {
            keyboard?.hide()
            focus.clearFocus()
            onSubmit()
        }
    }
    val inherited = MaterialTheme.colorScheme
    val palette = if (inherited.background.luminance() > 0.5f) inherited.copy(
        primary = Color(0xFF009A68), background = Color(0xFFFAFBF8), surface = Color(0xFFFAFBF8),
    ) else inherited
    MaterialTheme(colorScheme = palette) {
        Surface(modifier, color = palette.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxHeight < 800.dp || LocalDensity.current.fontScale > 1.3f
                AuthFormLayout(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = if (compact) Arrangement.Top else Arrangement.SpaceEvenly,
                    footerContent = {
                        if (compact) AuthSubmitButton(state, "Đăng nhập", submit)
                        AuthAccountSwitch("Chưa có tài khoản?", "Đăng ký", fieldsEnabled, onRegister)
                        if (!compact) AuthSessionBadge()
                    },
                ) {
                    AuthBrandHeader("Chào mừng trở lại!", "Đăng nhập để tiếp tục kế hoạch mua sắm của bạn.",
                        Modifier.padding(horizontal = 8.dp), logoWidth = if (compact) 128.dp else 200.dp)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                        RegistrationField(state.email, { onEdit("email", it) }, "Email", "example@email.com", R.drawable.ic_auth_mail,
                            fieldsEnabled, state.errors["email"],
                            KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            containerColor = palette.surfaceContainerLowest, unfocusedBorderColor = palette.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                        RegistrationField(state.password, { onEdit("password", it) }, "Mật khẩu", "Nhập mật khẩu", R.drawable.ic_auth_lock,
                            fieldsEnabled, state.errors["password"],
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { PasswordToggle(visible, "mật khẩu", fieldsEnabled) { visible = !visible } },
                            containerColor = palette.surfaceContainerLowest, unfocusedBorderColor = palette.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onForgot, enabled = fieldsEnabled) { Text("Quên mật khẩu?") }
                        }
                        state.message?.let {
                            Text(it, Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                color = palette.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!compact) AuthSubmitButton(state, "Đăng nhập", submit)
                        AuthDivider("hoặc")
                        GoogleAuthButton(onGoogle, fieldsEnabled && state.retrySeconds == 0)
                        if (compact) {
                            Spacer(Modifier.height(16.dp))
                            AuthSessionBadge()
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginPreview() {
    SmartShoppingTheme { LoginContent(RegisterUiState(page = AuthPage.LOGIN_ENTRY), { _, _ -> }, {}, {}) }
}
