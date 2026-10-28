package com.toked.smartshopping.auth

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (!state.sending && state.retrySeconds == 0) {
            keyboard?.hide()
            focus.clearFocus()
            onSubmit()
        }
    }
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 800.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        AuthFormLayout(
            modifier = Modifier.fillMaxSize(),
            topBar = { AuthTopBar("Đăng ký", onLogin, fieldsEnabled, titleAlign = TextAlign.Center) },
            footerContent = {
                if (compact) AuthSubmitButton(state, "Tạo tài khoản", submit, textStyle = MaterialTheme.typography.titleMedium)
                AuthAccountSwitch("Đã có tài khoản?", "Đăng nhập", fieldsEnabled, onLogin)
                if (!compact) AuthSessionBadge()
            },
        ) {
            AuthBrandHeader("Tạo tài khoản", "Bắt đầu lên kế hoạch mua sắm thông minh hơn.",
                logoWidth = if (compact) 64.dp else 136.dp, titleStyle = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            GoogleAuthButton(onGoogle, fieldsEnabled && state.retrySeconds == 0)
            AuthDivider("HOẶC ĐĂNG KÝ BẰNG EMAIL")
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerLowest, shadowElevation = 1.dp) {
                Column(Modifier.padding(if (compact) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RegistrationField(state.email, { onEdit("email", it) }, "Email", "email@gmail.com", R.drawable.ic_auth_mail,
                        fieldsEnabled, state.errors["email"],
                        KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
                    RegistrationField(state.password, { onEdit("password", it) }, "Mật khẩu", "Nhập mật khẩu", R.drawable.ic_auth_lock,
                        fieldsEnabled, state.errors["password"],
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        helperText = passwordHint,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordToggle(showPassword, "mật khẩu", fieldsEnabled) { showPassword = !showPassword } })
                    RegistrationField(state.confirmation, { onEdit("confirm_password", it) }, "Xác nhận mật khẩu", "Nhập lại mật khẩu", R.drawable.ic_auth_lock,
                        fieldsEnabled, state.errors["confirm_password"],
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        visualTransformation = if (showConfirmation) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordToggle(showConfirmation, "mật khẩu xác nhận", fieldsEnabled) { showConfirmation = !showConfirmation } })
                    state.message?.let { message ->
                        Surface(shape = RoundedCornerShape(12.dp), color = colors.errorContainer) {
                            Text(message, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!compact) AuthSubmitButton(state, "Tạo tài khoản", submit, textStyle = MaterialTheme.typography.titleMedium)
                }
            }
            if (compact) {
                Spacer(Modifier.height(16.dp))
                AuthSessionBadge()
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
