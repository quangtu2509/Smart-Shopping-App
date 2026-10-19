package com.toked.smartshopping.auth

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R

@Composable
fun RegisterContent(
    state: RegisterUiState,
    onEdit: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    onGoogle: () -> Unit = {},
) {
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val fieldsEnabled = !state.sending && !state.savingSession
    val canSubmit = !state.sending && state.retrySeconds == 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            focus.clearFocus()
            onSubmit()
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onLogin, enabled = !state.sending) {
                Icon(painterResource(R.drawable.ic_auth_arrow_back), contentDescription = "Quay lại đăng nhập")
            }
            Text("Đăng ký", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(48.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Use the original Stitch logo as a bundled asset, with its original white backdrop.
                Image(
                    painterResource(R.drawable.smart_shopping_logo),
                    contentDescription = "Smart Shopping",
                    modifier = Modifier.width(136.dp).height(102.dp).clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text("Tạo tài khoản", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bắt đầu lên kế hoạch mua sắm thông minh hơn.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                GoogleAuthButton(onGoogle,fieldsEnabled && state.retrySeconds == 0)
                Spacer(Modifier.height(16.dp))
                Text("Hoặc đăng ký bằng Gmail",style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = colors.surfaceContainerLowest,
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RegistrationField(
                            value = state.email,
                            onValueChange = { onEdit("email", it) },
                            label = "Email",
                            placeholder = "email@gmail.com",
                            leadingIcon = R.drawable.ic_auth_mail,
                            enabled = fieldsEnabled,
                            errorMessage = state.errors["email"],
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        )
                        RegistrationField(
                            value = state.password,
                            onValueChange = { onEdit("password", it) },
                            label = "Mật khẩu",
                            placeholder = "Nhập mật khẩu",
                            leadingIcon = R.drawable.ic_auth_lock,
                            enabled = fieldsEnabled,
                            errorMessage = state.errors["password"],
                            helperText = passwordHint,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                PasswordToggle(showPassword, "mật khẩu", fieldsEnabled) { showPassword = !showPassword }
                            },
                        )
                        RegistrationField(
                            value = state.confirmation,
                            onValueChange = { onEdit("confirm_password", it) },
                            label = "Xác nhận mật khẩu",
                            placeholder = "Nhập lại mật khẩu",
                            leadingIcon = R.drawable.ic_auth_lock,
                            enabled = fieldsEnabled,
                            errorMessage = state.errors["confirm_password"],
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            visualTransformation = if (showConfirmation) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                PasswordToggle(showConfirmation, "mật khẩu xác nhận", fieldsEnabled) { showConfirmation = !showConfirmation }
                            },
                        )
                        state.message?.let { message ->
                            Surface(shape = RoundedCornerShape(12.dp), color = colors.errorContainer) {
                                Text(
                                    message,
                                    Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                    color = colors.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(
                            onClick = submit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            enabled = canSubmit,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            if (state.sending) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                when {
                                    state.sending -> "Đang gửi…"
                                    state.retrySeconds > 0 -> "Thử lại sau ${state.retrySeconds}s"
                                    state.savingSession -> "Lưu lại phiên đăng nhập"
                                    else -> "Tạo tài khoản"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            if (!state.sending && state.retrySeconds == 0) {
                                Spacer(Modifier.width(8.dp))
                                Icon(painterResource(R.drawable.ic_auth_arrow_forward), null, Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Đã có tài khoản?", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TextButton(onLogin, enabled = !state.sending) { Text("Đăng nhập") }
                }
                Spacer(Modifier.height(8.dp))
                Surface(shape = CircleShape, color = colors.secondaryContainer) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_auth_shield), null, Modifier.size(18.dp), tint = colors.onSecondaryContainer)
                        Text(
                            "Phiên được mã hóa trên thiết bị",
                            color = colors.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun RegistrationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    @DrawableRes leadingIcon: Int,
    enabled: Boolean,
    errorMessage: String?,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    helperText: String? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedBorderColor: Color = Color.Transparent,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    errorMessage?.let { error(it) }
                },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.6f)),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (focused) colors.surfaceContainer else containerColor, RoundedCornerShape(12.dp))
                        .border(1.dp, when { errorMessage != null -> colors.error; focused -> colors.primary; else -> unfocusedBorderColor }, RoundedCornerShape(12.dp))
                        .padding(start = 16.dp, end = if (trailingIcon == null) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painterResource(leadingIcon), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
        val supportingText = errorMessage ?: helperText
        supportingText?.let {
            Text(
                it,
                Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (errorMessage != null) colors.error else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun PasswordToggle(visible: Boolean, field: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) {
        Icon(
            painterResource(if (visible) R.drawable.ic_auth_visibility_off else R.drawable.ic_auth_visibility),
            contentDescription = "${if (visible) "Ẩn" else "Hiện"} $field",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
