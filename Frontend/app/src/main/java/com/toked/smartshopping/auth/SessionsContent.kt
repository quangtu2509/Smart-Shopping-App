package com.toked.smartshopping.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SessionsContent(state: RegisterUiState, onReload: () -> Unit, onMore: () -> Unit,
    onRevoke: (String) -> Unit, onLogout: (Boolean) -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<AuthSessionSummary?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Phiên đăng nhập", style = MaterialTheme.typography.headlineMedium)
        Text(state.account?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.sessions.forEach { session ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session.isCurrent) "Thiết bị này" else session.deviceName ?: "Thiết bị chưa đặt tên",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Đăng nhập: ${sessionDate(session.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    Text("Hoạt động gần nhất: ${sessionDate(session.lastUsedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text("Hết hạn: ${sessionDate(session.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ selected = session }, enabled = !state.sending) {
                        Text(if (session.isCurrent) "Đăng xuất thiết bị này" else "Thu hồi phiên")
                    }
                }
            }
        }
        if (!state.sending && state.sessions.isEmpty() && state.message == null) Text("Không có phiên đăng nhập đang hoạt động.")
        if (state.nextCursor != null) OutlinedButton(onMore, enabled = !state.sending) { Text("Xem thêm phiên") }
        OutlinedButton(onReload, enabled = !state.sending) { Text("Tải lại danh sách") }
        Button({ confirmAll = true }, enabled = !state.sending) { Text("Đăng xuất tất cả thiết bị") }
        TextButton(onHome, enabled = !state.sending) { Text("Về trang chủ") }
    }
    selected?.let { session ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Thu hồi phiên đăng nhập?") },
            text = { Text(if (session.isCurrent) "Bạn sẽ đăng xuất khỏi thiết bị này." else "Thiết bị đó sẽ cần đăng nhập lại. Dữ liệu mua sắm được giữ nguyên.") },
            confirmButton = { TextButton({ selected = null; onRevoke(session.id) }) { Text("Xác nhận thu hồi") } },
            dismissButton = { TextButton({ selected = null }) { Text("Hủy") } })
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text("Đăng xuất tất cả thiết bị?") },
        text = { Text("Bạn và các thiết bị khác sẽ cần đăng nhập lại. Dữ liệu mua sắm được giữ nguyên.") },
        confirmButton = { TextButton({ confirmAll = false; onLogout(true) }) { Text("Xác nhận đăng xuất") } },
        dismissButton = { TextButton({ confirmAll = false }) { Text("Hủy") } })
}

private fun sessionDate(value: String): String = try {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: java.time.format.DateTimeParseException) { "Không xác định" }
