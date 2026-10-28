package com.toked.smartshopping.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.toked.smartshopping.R

@Composable
internal fun GoogleAuthButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF202B3A))) {
        Image(painterResource(R.drawable.ic_google), null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Tiếp tục với Google", textAlign = TextAlign.Center)
    }
}

/** Stitch surfaces with application-owned values and callbacks; no prototype data or timers. */
@Composable
fun AuthStepContent(state: RegisterUiState, onEdit: (String, String) -> Unit, onSubmit: () -> Unit,
    onBack: () -> Unit, onResend: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    if (state.page == AuthPage.REGISTER_SUCCESS) {
        RegistrationSuccessContent(state, onContinue, modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    var visible by remember(state.page) { mutableStateOf(false) }
    var confirmVisible by remember(state.page) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { if (!state.sending && state.retrySeconds == 0) { keyboard?.hide(); focus.clearFocus(); onSubmit() } }
    val enabled = !state.sending && !state.savingSession
    val otp = state.page in setOf(AuthPage.VERIFY_EMAIL, AuthPage.VERIFY_RESET)
    val reset = state.page == AuthPage.RESET_PASSWORD
    if (state.page == AuthPage.FORGOT) {
        ForgotPasswordContent(state, { onEdit("email", it) }, submit, onBack, modifier)
        return
    }
    val title = when {
        reset -> "Đặt lại mật khẩu mới"
        otp -> "Xác thực mã OTP"
        else -> "Liên kết tài khoản Google"
    }
    Column(modifier) {
        AuthTopBar(when { otp -> "Xác minh email"; reset -> "Mật khẩu mới"; else -> "Liên kết Google" }, onBack, enabled)
        AuthScrollContent(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Top) {
            Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping",
                Modifier.padding(vertical = 8.dp).width(if (otp) 144.dp else 104.dp).height(if (otp) 80.dp else 78.dp))
            if (!otp) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(when {
                        reset -> "Chọn mật khẩu mới để bảo vệ tài khoản và kế hoạch mua sắm của bạn."
                        else -> "Email này đã có tài khoản. Xác nhận mật khẩu Smart Shopping hiện tại để giữ chung dữ liệu."
                    }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest,
                shadowElevation = 1.dp) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (otp) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text("Mã xác thực gồm 6 chữ số đã được gửi đến", style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer) {
                            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_auth_mail), null, Modifier.size(16.dp), tint = colors.primary)
                                Text(state.email, Modifier.weight(1f, fill = false).padding(horizontal = 8.dp),
                                    style = MaterialTheme.typography.labelLarge)
                                IconButton(onBack, enabled = enabled) {
                                    Icon(painterResource(R.drawable.ic_auth_edit), "Đổi email", Modifier.size(18.dp))
                                }
                            }
                        }
                        OtpField(state, enabled, { onEdit("code", it) }, submit)
                        Text("Mã có hiệu lực tối đa 10 phút.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        TextButton(onResend, enabled = enabled && state.resendSeconds == 0 && state.retrySeconds == 0) {
                            Text(if (state.resendSeconds > 0) "Gửi lại mã sau ${state.resendSeconds}s" else "Gửi lại mã")
                        }
                    }
                    if (reset || state.page == AuthPage.LINK_GOOGLE) {
                        RegistrationField(state.password, { onEdit("password", it) }, "Mật khẩu", "Nhập mật khẩu mới", R.drawable.ic_auth_lock,
                            enabled, state.errors["password"],
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (reset) ImeAction.Next else ImeAction.Done),
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { PasswordToggle(visible, "mật khẩu", enabled) { visible = !visible } },
                            keyboardActions = KeyboardActions(onDone = { submit() }))
                        if (reset) PasswordRequirements(state.password)
                    }
                    if (reset) {
                        RegistrationField(state.confirmation, { onEdit("confirm_password", it) }, "Xác nhận mật khẩu", "Nhập lại mật khẩu", R.drawable.ic_auth_lock,
                            enabled, state.errors["confirm_password"], KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { PasswordToggle(confirmVisible, "mật khẩu xác nhận", enabled) { confirmVisible = !confirmVisible } },
                            keyboardActions = KeyboardActions(onDone = { submit() }))
                        if (state.confirmation.isNotEmpty() && state.confirmation == state.password) {
                            Text("Mật khẩu hoàn toàn trùng khớp", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                        }
                    }
                    state.message?.let {
                        Text(it, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    AuthSubmitButton(state, when { reset -> "Đặt lại mật khẩu"; otp -> "Xác minh"; else -> "Xác nhận liên kết" }, submit)
                    AuthHint(when {
                        otp -> "Chưa thấy mã? Kiểm tra cả hộp thư rác (Spam) và chờ trước khi gửi lại."
                        reset -> "Không chia sẻ mật khẩu để bảo vệ tài khoản và kế hoạch của bạn."
                        else -> "Chỉ xác nhận liên kết khi bạn vừa chọn đăng nhập bằng Google."
                    })
                }
            }
            if (otp) TextButton(onBack, enabled = enabled) { Text("Đổi email hoặc bắt đầu lại") }
            Spacer(Modifier.height(20.dp))
            AuthSessionBadge()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ForgotPasswordContent(state: RegisterUiState, onEmail: (String) -> Unit, onSubmit: () -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val enabled = !state.sending && !state.savingSession
    Column(modifier) {
        AuthTopBar("Quên mật khẩu", onBack, enabled)
        AuthScrollContent(Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quên mật khẩu?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("Đừng lo lắng! Nhập email tài khoản để nhận mã xác thực đặt lại mật khẩu.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest, shadowElevation = 1.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RegistrationField(state.email, onEmail, "Email", "example@email.com", R.drawable.ic_auth_mail,
                        enabled, state.errors["email"], KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        helperText = "Chúng tôi sẽ gửi mã OTP gồm 6 chữ số đến email này.",
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }))
                    state.message?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    AuthSubmitButton(state, "Gửi mã xác minh", onSubmit)
                    AuthHint("Dùng email đã đăng ký tài khoản. Nếu đăng nhập bằng Google, hãy tiếp tục với Google.")
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthAccountSwitch("Nhớ lại mật khẩu?", "Đăng nhập ngay", enabled, onBack)
                AuthSessionBadge()
            }
        }
    }
}

@Composable
private fun OtpField(state: RegisterUiState, enabled: Boolean, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(state.code, { onChange(it.filter { char -> char in '0'..'9' }.take(6)) },
        Modifier.fillMaxWidth().semantics {
            contentDescription = "Mã xác minh"
            state.errors["code"]?.let { error(it) }
        }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(6) { index ->
                        val filled = index < state.code.length
                        Box(Modifier.weight(1f).heightIn(min = 56.dp)
                            .background(if (filled) colors.secondaryContainer.copy(alpha = 0.5f) else colors.surfaceContainerLow, RoundedCornerShape(12.dp))
                            .border(1.dp, if (state.errors["code"] != null) colors.error else if (index == state.code.length) colors.primary else Color.Transparent,
                                RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(state.code.getOrNull(index)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                        }
                    }
                }
            }
        })
    state.errors["code"]?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PasswordRequirements(password: String) {
    val points = password.codePoints().toArray()
    val requirements = listOf(
        "Từ 10–128 ký tự" to (points.size in 10..128),
        "Có chữ hoa" to points.any(Character::isUpperCase),
        "Có chữ thường" to points.any(Character::isLowerCase),
        "Có ký tự đặc biệt (không tính dấu cách)" to points.any {
            Character.getType(it) in Character.DASH_PUNCTUATION.toInt()..Character.FINAL_QUOTE_PUNCTUATION.toInt()
        },
    )
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            requirements.forEach { (_, met) ->
                Box(Modifier.weight(1f).height(4.dp).background(if (met) colors.primary else colors.outlineVariant, CircleShape))
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerLow) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                requirements.forEach { (label, met) ->
                    Row(Modifier.semantics(mergeDescendants = true) {
                        stateDescription = if (met) "Đã đáp ứng" else "Chưa đáp ứng"
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(if (met) R.drawable.ic_auth_check else R.drawable.ic_auth_lock), null,
                            Modifier.size(16.dp), tint = if (met) colors.primary else colors.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessMark() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(104.dp).background(colors.secondaryContainer, CircleShape).padding(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_auth_check), null, Modifier.size(44.dp), tint = colors.onPrimary)
        }
    }
}

@Composable
private fun RegistrationSuccessContent(state: RegisterUiState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping", Modifier.width(200.dp).height(150.dp))
        Text("Chào mừng trở lại!", style = MaterialTheme.typography.headlineMedium)
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest) {
                Column(Modifier.heightIn(max = maxHeight - 48.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SuccessMark()
                    Text("Đăng ký thành công!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("Chào mừng bạn gia nhập Smart Shopping! Email đã được xác minh. Tiếp tục để hoàn thiện hồ sơ mua sắm.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.account?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Text("Đã kích hoạt", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Button(onContinue, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("Tiếp tục") }
                }
            }
        }
        }
    }
}

@Composable
internal fun ResetPasswordSuccessContent(onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        AuthTopBar("Mật khẩu đã cập nhật", onLogin, true)
        AuthScrollContent(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping", Modifier.width(104.dp).height(78.dp))
            SuccessMark()
            Text("Đổi mật khẩu thành công!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text("Bạn có thể đăng nhập lại bằng mật khẩu mới để tiếp tục kế hoạch mua sắm.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Chi tiết bảo mật", style = MaterialTheme.typography.titleMedium)
                    Text("Đã cập nhật mật khẩu", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    AuthHint("Các phiên đăng nhập cũ đã được thu hồi. Hãy dùng mật khẩu mới để đăng nhập lại.")
                }
            }
            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("Đăng nhập ngay") }
            AuthSessionBadge()
        }
    }
}
