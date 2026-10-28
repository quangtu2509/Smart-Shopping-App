package com.toked.smartshopping.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R

/** A bounded form scrolls independently of the actions, including when the IME resizes it. */
@Composable
internal fun AuthFormLayout(
    footerContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        topBar?.invoke()
        AuthScrollContent(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = verticalArrangement, content = content)
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally, content = footerContent)
            }
        }
    }
}

/** Distribute complete form sections across the viewport; overflow still scrolls at natural size. */
@Composable
internal fun AuthScrollContent(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.SpaceEvenly,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val viewportHeight = maxHeight
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Composable
internal fun AuthBrandHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    logoWidth: Dp = 176.dp,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping",
            Modifier.width(logoWidth).height(logoWidth * 0.75f).clip(RoundedCornerShape(24.dp)))
        Text(title, style = titleStyle, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AuthTopBar(title: String, onBack: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    titleAlign: TextAlign = TextAlign.Start) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_auth_arrow_back), "Quay lại đăng nhập")
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = titleAlign)
        Image(painterResource(R.drawable.smart_shopping_logo), null, Modifier.width(40.dp).height(30.dp))
    }
}

@Composable
internal fun AuthDivider(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
internal fun AuthSubmitButton(state: RegisterUiState, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = !state.sending && state.retrySeconds == 0, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        if (state.sending) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text(when {
            state.sending -> if (state.page == AuthPage.LOGIN_ENTRY) "Đang đăng nhập…" else "Đang gửi…"
            state.retrySeconds > 0 -> "Thử lại sau ${state.retrySeconds}s"
            state.savingSession -> "Lưu lại phiên đăng nhập"
            else -> label
        }, textAlign = TextAlign.Center, style = textStyle)
        if (!state.sending && state.retrySeconds == 0) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_auth_arrow_forward), null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun AuthAccountSwitch(prompt: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text(label) }
    }
}

@Composable
internal fun AuthHint(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_auth_shield), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AuthSessionBadge() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = colors.secondaryContainer) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_auth_shield), null, Modifier.size(18.dp), tint = colors.onSecondaryContainer)
            Text("Phiên được mã hóa trên thiết bị", color = colors.onSecondaryContainer,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
