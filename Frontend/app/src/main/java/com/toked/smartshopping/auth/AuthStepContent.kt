package com.toked.smartshopping.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R

@Composable
internal fun GoogleAuthButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF202B3A))) {
        Image(painterResource(R.drawable.ic_google), null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Tiếp tục với Google", textAlign = TextAlign.Center)
    }
}

/** Runtime-free content using the existing Stitch palette, typography, logo and rounded fields. */
@Composable
fun AuthStepContent(state: RegisterUiState, onEdit: (String,String) -> Unit, onSubmit: () -> Unit,
    onBack: () -> Unit, onResend: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var visible by remember(state.page) { mutableStateOf(false) }
    var confirmVisible by remember(state.page) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { if (!state.sending && state.retrySeconds == 0) { keyboard?.hide(); focus.clearFocus(); onSubmit() } }
    val enabled = !state.sending && !state.savingSession
    val isOtp = state.page in setOf(AuthPage.VERIFY_EMAIL,AuthPage.VERIFY_RESET)
    val success = state.page == AuthPage.REGISTER_SUCCESS
    val title = when (state.page) {
        AuthPage.FORGOT -> "Quên mật khẩu?"
        AuthPage.RESET_PASSWORD -> "Đặt lại mật khẩu mới"
        AuthPage.LINK_GOOGLE -> "Liên kết tài khoản Google"
        AuthPage.REGISTER_SUCCESS -> "Đăng ký thành công!"
        else -> "Xác thực mã OTP"
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!success) IconButton(onBack, enabled = !state.sending) {
                Icon(painterResource(R.drawable.ic_auth_arrow_back), "Quay lại đăng nhập")
            } else Spacer(Modifier.width(48.dp))
            Text(if (isOtp) "Xác minh email" else if (success) "Tài khoản sẵn sàng" else "Tài khoản",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(painterResource(R.drawable.smart_shopping_logo),"Smart Shopping",Modifier.size(width = 136.dp,height = 102.dp))
                Surface(shape = CircleShape,color = colors.primaryContainer) {
                    if (success) Box(Modifier.size(88.dp),contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_auth_check),null,Modifier.size(40.dp),tint = colors.onPrimaryContainer)
                    }
                    else Icon(painterResource(if (state.page == AuthPage.FORGOT || isOtp) R.drawable.ic_auth_mail else R.drawable.ic_auth_lock),
                        null,Modifier.padding(24.dp).size(32.dp),tint = colors.onPrimaryContainer)
                }
                Text(title,style = MaterialTheme.typography.headlineSmall,textAlign = TextAlign.Center)
                Text(when {
                    success -> "Email đã được xác minh. Bắt đầu lên kế hoạch mua sắm thông minh hơn."
                    isOtp -> "Nhập mã gồm 6 chữ số được gửi đến email của bạn. Mã có hiệu lực tối đa 10 phút."
                    state.page == AuthPage.FORGOT -> "Nhập email tài khoản để nhận mã khôi phục mật khẩu."
                    state.page == AuthPage.LINK_GOOGLE -> "Email này đã có tài khoản. Nhập mật khẩu Smart Shopping hiện tại để giữ chung dữ liệu."
                    else -> "Chọn mật khẩu mới để bảo vệ tài khoản của bạn."
                },textAlign = TextAlign.Center,color = colors.onSurfaceVariant,style = MaterialTheme.typography.bodyMedium)
                if (success) {
                    Text(state.account?.email.orEmpty(),style = MaterialTheme.typography.bodyMedium)
                    Button(onContinue,Modifier.fillMaxWidth().heightIn(min = 54.dp),shape = RoundedCornerShape(16.dp)) { Text("Tiếp tục") }
                } else {
                    Surface(shape = RoundedCornerShape(24.dp),color = colors.surfaceContainerLowest) {
                        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (state.page == AuthPage.FORGOT) RegistrationField(state.email,{ onEdit("email",it) },"Email","email@gmail.com",R.drawable.ic_auth_mail,
                                enabled,state.errors["email"],KeyboardOptions(keyboardType = KeyboardType.Email,imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }))
                            if (isOtp) {
                                Text("Mã xác minh",style = MaterialTheme.typography.labelMedium)
                                BasicTextField(state.code,{ onEdit("code",it.filter { char -> char in '0'..'9' }.take(6)) },
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = "Mã xác minh"
                                        state.errors["code"]?.let { error(it) }
                                    },enabled = enabled,singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword,imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth()) {
                                            Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                repeat(6) { index ->
                                                    Box(Modifier.weight(1f).heightIn(min = 56.dp).border(1.dp,
                                                        if (state.errors["code"] != null) colors.error else if (index == state.code.length) colors.primary else colors.outlineVariant,
                                                        RoundedCornerShape(12.dp)),contentAlignment = Alignment.Center) {
                                                        Text(state.code.getOrNull(index)?.toString().orEmpty(),style = MaterialTheme.typography.titleLarge)
                                                    }
                                                }
                                            }
                                        }
                                    })
                                state.errors["code"]?.let { Text(it,color = colors.error,style = MaterialTheme.typography.bodySmall) }
                            }
                            if (state.page in setOf(AuthPage.RESET_PASSWORD,AuthPage.LINK_GOOGLE)) {
                                RegistrationField(state.password,{ onEdit("password",it) },"Mật khẩu", "Nhập mật khẩu",R.drawable.ic_auth_lock,
                                    enabled,state.errors["password"],KeyboardOptions(keyboardType = KeyboardType.Password,imeAction = if (state.page == AuthPage.RESET_PASSWORD) ImeAction.Next else ImeAction.Done),
                                    helperText = if (state.page == AuthPage.RESET_PASSWORD) passwordHint else null,
                                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = { PasswordToggle(visible,"mật khẩu",enabled) { visible = !visible } },
                                    keyboardActions = KeyboardActions(onDone = { submit() }))
                            }
                            if (state.page == AuthPage.RESET_PASSWORD) RegistrationField(state.confirmation,{ onEdit("confirm_password",it) },"Xác nhận mật khẩu","Nhập lại mật khẩu",R.drawable.ic_auth_lock,
                                enabled,state.errors["confirm_password"],KeyboardOptions(keyboardType = KeyboardType.Password,imeAction = ImeAction.Done),
                                visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { PasswordToggle(confirmVisible,"mật khẩu xác nhận",enabled) { confirmVisible = !confirmVisible } },
                                keyboardActions = KeyboardActions(onDone = { submit() }))
                            state.message?.let { Text(it,Modifier.semantics { liveRegion = LiveRegionMode.Polite },color = colors.onSurfaceVariant,style = MaterialTheme.typography.bodySmall) }
                            Button(submit,Modifier.fillMaxWidth().heightIn(min = 54.dp),enabled = !state.sending && state.retrySeconds == 0,shape = RoundedCornerShape(16.dp)) {
                                if (state.sending) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth = 2.dp,color = colors.onPrimary)
                                else Text(when {
                                    state.savingSession -> "Lưu lại phiên đăng nhập"
                                    state.retrySeconds > 0 -> "Thử lại sau ${state.retrySeconds}s"
                                    state.page == AuthPage.FORGOT -> "Gửi mã xác minh"
                                    state.page == AuthPage.RESET_PASSWORD -> "Đặt lại mật khẩu"
                                    state.page == AuthPage.LINK_GOOGLE -> "Xác nhận liên kết"
                                    else -> "Xác minh"
                                },textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (isOtp) {
                        TextButton(onResend,enabled = !state.sending && !state.savingSession && state.resendSeconds == 0 && state.retrySeconds == 0) {
                            Text(if (state.resendSeconds > 0) "Gửi lại mã sau ${state.resendSeconds}s" else "Gửi lại mã")
                        }
                        TextButton(onBack,enabled = !state.sending) { Text("Đổi email hoặc bắt đầu lại") }
                    }
                }
            }
        }
    }
}
