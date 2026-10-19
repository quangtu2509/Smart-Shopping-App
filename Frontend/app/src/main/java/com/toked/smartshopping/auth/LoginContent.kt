package com.toked.smartshopping.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R
import com.toked.smartshopping.ui.theme.SmartShoppingTheme

@Composable
fun LoginContent(state: RegisterUiState, onEdit: (String, String) -> Unit, onSubmit: () -> Unit,
    onRegister: () -> Unit, modifier: Modifier = Modifier, onGoogle: () -> Unit = {}, onForgot: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val canSubmit = !state.sending && state.retrySeconds == 0
    val fieldsEnabled = !state.sending && !state.savingSession
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            focus.clearFocus()
            onSubmit()
        }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping",
                Modifier.width(200.dp).height(150.dp).clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(16.dp))
            Text("Chào mừng trở lại!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Đăng nhập để tiếp tục kế hoạch mua sắm của bạn.", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            RegistrationField(state.email, { onEdit("email", it) }, "Email", "example@email.com", R.drawable.ic_auth_mail,
                fieldsEnabled, state.errors["email"],
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                containerColor = colors.surfaceContainerLowest, unfocusedBorderColor = colors.outlineVariant)
            Spacer(Modifier.height(16.dp))
            RegistrationField(state.password, { onEdit("password", it) }, "Mật khẩu", "Nhập mật khẩu", R.drawable.ic_auth_lock,
                fieldsEnabled, state.errors["password"],
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { PasswordToggle(visible, "mật khẩu", fieldsEnabled) { visible = !visible } },
                containerColor = colors.surfaceContainerLowest, unfocusedBorderColor = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) {
                TextButton(onForgot,enabled = fieldsEnabled) { Text("Quên mật khẩu?") }
            }
            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(submit, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = canSubmit,
                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp)) {
                if (state.sending) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(when {
                    state.sending -> "Đang đăng nhập…"
                    state.retrySeconds > 0 -> "Thử lại sau ${state.retrySeconds}s"
                    state.savingSession -> "Lưu lại phiên đăng nhập"
                    else -> "Đăng nhập"
                }, textAlign = TextAlign.Center)
                if (!state.sending && state.retrySeconds == 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.ic_auth_arrow_forward), null, Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Hoặc",color = colors.onSurfaceVariant,style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            GoogleAuthButton(onGoogle,fieldsEnabled && state.retrySeconds == 0)
            Spacer(Modifier.height(28.dp))
            FlowRow(horizontalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Chưa có tài khoản?", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                TextButton(onRegister, enabled = !state.sending) { Text("Đăng ký") }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = CircleShape, color = colors.secondaryContainer) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_auth_shield), null, Modifier.size(18.dp), tint = colors.onSecondaryContainer)
                    Text("Phiên được mã hóa trên thiết bị", color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
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
