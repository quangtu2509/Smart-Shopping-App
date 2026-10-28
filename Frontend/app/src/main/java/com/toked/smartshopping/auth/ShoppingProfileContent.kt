package com.toked.smartshopping.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoppingProfileContent(
    state: ProfileUiState,
    onEdit: (String, String) -> Unit,
    onSelect: (String, String, String) -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    onReload: () -> Unit,
    onRetryReferences: () -> Unit,
    onDialog: (String) -> Unit,
    modifier: Modifier = Modifier,
    ownerEmail: String = "",
) {
    val focusManager = LocalFocusManager.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onLeave, enabled = !state.saving) { Text("Quay lại") }
                Text("Hồ sơ mua sắm", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Thiết lập mua sắm", style = MaterialTheme.typography.headlineMedium)
                if (ownerEmail.isNotBlank()) Text(ownerEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Điều chỉnh thói quen và ưu tiên để có gợi ý phù hợp hơn.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text("Đang tải hồ sơ…")
                }
                state.message?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (state.saved == null) TextButton(onReload, enabled = !state.loading) { Text("Thử tải lại") }
                        }
                    }
                }
                if (state.saved != null) {
                    if (!state.dirty && state.saved == ShoppingProfile()) Text("Hồ sơ mặc định", style = MaterialTheme.typography.titleMedium)
                    Text("Các trường có thể để trống. Mặc định: tối đa 3 cửa hàng, chế độ Cân bằng. Hồ sơ không thay đổi kế hoạch đã có.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(state.draft.household, { onEdit("household_size", it) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Số người trong gia đình (tùy chọn)") }, singleLine = true, enabled = state.editable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), isError = "household_size" in state.errors,
                        supportingText = { Text(state.errors["household_size"] ?: "Để trống nếu chưa muốn thiết lập.") })
                    OutlinedTextField(state.draft.period, { onEdit("default_period", it) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Chu kỳ mua sắm (tùy chọn)") }, singleLine = true, enabled = state.editable,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        isError = "default_period" in state.errors, supportingText = { Text(state.errors["default_period"] ?: "Ví dụ: 2 tuần, 1 tháng hoặc 1 tháng 3 ngày.") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf("" to "Chưa thiết lập", "1 tuần" to "Hàng tuần", "2 tuần" to "Hai tuần", "1 tháng" to "Hàng tháng")) {
                            FilterChip(state.draft.period == value, { onEdit("default_period", value) }, label = { Text(label) }, enabled = state.editable)
                        }
                    }
                    HorizontalDivider()
                    Text("Số cửa hàng tối đa", style = MaterialTheme.typography.titleMedium)
                    Text("Giới hạn từ 1 đến 3 cửa hàng cho phương án mua sắm.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (count in 1..3) FilterChip(state.draft.maxStores == count, { onEdit("max_stores", count.toString()) },
                            label = { Text("$count cửa hàng") }, enabled = state.editable)
                    }
                    Text("Chế độ tối ưu mặc định", style = MaterialTheme.typography.titleMedium)
                    for ((mode, label) in profileModes) {
                        Row(Modifier.fillMaxWidth().selectable(selected = state.draft.mode == mode, enabled = state.editable,
                            role = Role.RadioButton, onClick = { onEdit("optimization_mode", mode) }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.draft.mode == mode, onClick = null, enabled = state.editable)
                            Text(label, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                    Text("Tiết kiệm nhất ưu tiên chi phí; Thuận tiện nhất ưu tiên việc di chuyển; Cân bằng kết hợp cả hai.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    if (state.referenceError) {
                        Text("Chưa tải được danh mục và thương hiệu. Những lựa chọn đã lưu vẫn được giữ.", color = MaterialTheme.colorScheme.error)
                        TextButton(onRetryReferences, enabled = state.editable) { Text("Thử tải lựa chọn") }
                    }
                    if (state.referencesLoading) Text("Đang tải danh mục và thương hiệu…")
                    if (!state.referencesLoading) ReferenceChoices("Nhóm hàng", "categories", state.categories, state.draft.preferredCategories, state.draft.avoidedCategories,
                        state.editable, onSelect)
                    if (!state.referencesLoading) ReferenceChoices("Thương hiệu", "brands", state.brands, state.draft.preferredBrands, state.draft.avoidedBrands,
                        state.editable, onSelect)
                    state.errors.filterKeys { it !in setOf("household_size", "default_period") }.forEach { (field, text) ->
                        Text("${if (field.contains("brand")) "Thương hiệu" else if (field.contains("category")) "Nhóm hàng" else "Lựa chọn"}: $text", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton({ onDialog("reload") }, enabled = !state.loading && !state.saving) { Text("Tải lại hồ sơ đã lưu") }
                }
                Spacer(Modifier.height(8.dp))
            }
            Surface(shadowElevation = 4.dp) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onSave, Modifier.fillMaxWidth(), enabled = !state.loading && !state.saving && state.saved != null && !state.conflict) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (state.saving) "  Đang lưu…" else if (state.uncertainSave) "Thử lưu lại" else "Lưu hồ sơ")
                    }
                    TextButton(onLeave, Modifier.fillMaxWidth(), enabled = !state.saving) { Text("Bỏ qua và tiếp tục") }
                }
            }
        }
    }
    if (state.confirmLeave || state.confirmReload) {
        AlertDialog(onDismissRequest = { onDialog("cancel") }, title = { Text(if (state.confirmReload) "Tải lại hồ sơ?" else "Rời hồ sơ mua sắm?") },
            text = { Text(if (state.uncertainSave) "Kết quả lưu chưa được xác nhận. Bạn có thể tải lại để xem hồ sơ hiện tại. Bản nhập trên màn hình sẽ bị bỏ."
                else "Những thay đổi chưa lưu trên màn hình sẽ bị bỏ.") },
            confirmButton = { TextButton({ onDialog(if (state.confirmReload) "confirmReload" else "discard") }) { Text(if (state.confirmReload) "Tải bản mới" else "Bỏ thay đổi") } },
            dismissButton = { TextButton({ onDialog("cancel") }) { Text("Tiếp tục chỉnh sửa") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceChoices(title: String, kind: String, references: List<ProfileReference>, preferred: List<String>, avoided: List<String>,
    enabled: Boolean, onSelect: (String, String, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val known = references.map { it.id }.toSet()
    val entries = references + (preferred + avoided).distinct().filter { it !in known }.map { ProfileReference(it, "Lựa chọn đã lưu ($it)", "INACTIVE") }
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text("Chọn ưu tiên, cần tránh hoặc để trống. Một lựa chọn chỉ thuộc một nhóm.", style = MaterialTheme.typography.bodySmall)
    if (entries.isEmpty()) Text("Chưa có dữ liệu ${title.lowercase()}. Bạn có thể lưu các tùy chọn khác và bổ sung sau.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        OutlinedTextField(query, { query = it }, label = { Text("Tìm ${title.lowercase()}") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val visible = entries.filter { it.name.contains(query, ignoreCase = true) && (it.status == "ACTIVE" || it.id in preferred || it.id in avoided) }
        if (visible.isEmpty()) Text("Không có lựa chọn phù hợp.")
        for (entry in visible) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name + if (entry.status != "ACTIVE") " · Ngừng sử dụng" else "", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(entry.id in preferred, { onSelect(kind, entry.id, "preferred") },
                        label = { Text("Ưu tiên ${entry.name}") }, enabled = enabled && (entry.status == "ACTIVE" || entry.id in preferred))
                    FilterChip(entry.id in avoided, { onSelect(kind, entry.id, "avoided") },
                        label = { Text("Tránh ${entry.name}") }, enabled = enabled && (entry.status == "ACTIVE" || entry.id in avoided))
                }
            }
        }
    }
}
