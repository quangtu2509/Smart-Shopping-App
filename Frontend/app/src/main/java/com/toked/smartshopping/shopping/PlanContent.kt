package com.toked.smartshopping.shopping

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R

@Composable
fun PlanContent(state: PlanUiState, onAction: (PlanAction) -> Unit, modifier: Modifier = Modifier,
    catalog: CatalogUiState = CatalogUiState(), onCatalogAction: (CatalogAction)->Unit = {}) {
    if(state.catalogOpen) { CatalogContent(catalog,state,onCatalogAction,{onAction(PlanAction.Back)},modifier);return }
    BackHandler { onAction(PlanAction.Back) }
    val colors = MaterialTheme.colorScheme
    val number = when (state.step) { PlanStep.BUDGET -> 1; PlanStep.STORES -> 2; PlanStep.CATEGORIES -> 3; PlanStep.CLOSED, PlanStep.DETAIL -> 0 }
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onAction(PlanAction.Back) }, enabled = !state.saving && !state.opening) {
                    Icon(painterResource(R.drawable.ic_auth_arrow_back), "Quay lại")
                }
                Text(if (number == 0) "Kế hoạch của bạn" else "Tạo kế hoạch", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Image(painterResource(R.drawable.smart_shopping_logo), "Smart Shopping", Modifier.size(width = 56.dp, height = 36.dp))
            }
            if (number > 0) PlanProgress(number)
            key(state.step) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.message?.let { PlanNotice(it, error = state.saved == null || state.step != PlanStep.DETAIL) }
                        when (state.step) {
                            PlanStep.BUDGET -> BudgetStep(state, onAction)
                            PlanStep.STORES -> StoreStep(state, onAction)
                            PlanStep.CATEGORIES -> CategoryStep(state, onAction)
                            PlanStep.DETAIL -> PlanDetail(state)
                            PlanStep.CLOSED -> Unit
                        }
                    }
                }
            }
            Surface(shadowElevation = 3.dp, color = colors.surface) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val inputError = when (state.step) {
                            PlanStep.BUDGET -> state.errors["budget"]
                            PlanStep.STORES -> state.errors["selections"]
                            PlanStep.CATEGORIES -> state.errors["category_ids"]
                            PlanStep.CLOSED, PlanStep.DETAIL -> null
                        }
                        inputError?.let { ErrorText(it) }
                        val canEdit = state.saved?.plan?.status in editablePlans
                        Button(onClick = { onAction(when {
                            state.step == PlanStep.DETAIL -> PlanAction.Browse
                            state.pending != null -> PlanAction.Save
                            else -> PlanAction.Continue
                        }) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            enabled = !state.saving && !state.opening && state.retrySeconds == 0,
                            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                            if (state.saving) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary); Spacer(Modifier.width(8.dp)) }
                            Text(when {
                                state.saving -> "Đang lưu…"
                                state.retrySeconds > 0 -> "Thử lại sau ${state.retrySeconds}s"
                                state.pending != null -> "Thử lưu lại"
                                state.step == PlanStep.DETAIL -> "Xem sản phẩm"
                                state.step == PlanStep.CATEGORIES && state.editing -> "Lưu thay đổi"
                                state.step == PlanStep.CATEGORIES -> "Lưu kế hoạch"
                                else -> "Tiếp tục"
                            }, textAlign = TextAlign.Center)
                            if (!state.saving) { Spacer(Modifier.width(8.dp)); Icon(painterResource(R.drawable.ic_auth_arrow_forward), null, Modifier.size(20.dp)) }
                        }
                        if (state.step == PlanStep.DETAIL) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(canEdit) TextButton({ onAction(PlanAction.Edit) }) { Text("Chỉnh sửa kế hoạch") }
                            TextButton({onAction(PlanAction.Back)}) {Text("Về trang chủ")}
                        }
                        else if (number > 0) Text(when (number) { 1 -> "Bước 2: Chọn nơi mua"; 2 -> "Đã chọn ${state.draft.selections.size}/${state.maxStores ?: 3} nơi mua"; else -> "${state.draft.categoryIds.size} nhóm hàng đã chọn" },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (state.confirmExit) AlertDialog(onDismissRequest = { onAction(PlanAction.CancelExit) }, title = { Text("Rời biểu mẫu?") },
        text = { Text(if (state.pending != null) "Kết quả lưu chưa được xác nhận. Nên thử lưu lại để tránh tạo nháp trùng. Nếu rời form, hãy kiểm tra danh sách kế hoạch trước khi tạo lại."
            else "Thông tin chưa lưu sẽ bị bỏ. Kế hoạch đã lưu của bạn vẫn được giữ.") },
        confirmButton = { TextButton({ onAction(PlanAction.ConfirmExit) }) { Text("Rời biểu mẫu") } },
        dismissButton = { TextButton({ onAction(PlanAction.CancelExit) }) { Text("Tiếp tục chỉnh sửa") } })
    if (state.conflict) AlertDialog(onDismissRequest = { onAction(PlanAction.CancelConflict) }, title = { Text("Kế hoạch đã thay đổi") },
        text = { Text("Thiết bị khác đã cập nhật kế hoạch. Tải bản mới sẽ thay thông tin đang nhập; thay đổi của bạn chưa được ghi đè lên bản đã lưu.") },
        confirmButton = { TextButton({ onAction(PlanAction.ReloadConflict) }) { Text("Tải bản mới") } },
        dismissButton = { TextButton({ onAction(PlanAction.CancelConflict) }) { Text("Giữ thông tin đang nhập") } })
}

@Composable
private fun PlanProgress(number: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BƯỚC $number/3", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text("Khởi tạo kế hoạch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Ngân sách", "Nơi mua", "Nhóm hàng").forEachIndexed { i, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (i < number) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Text("${i + 1}. $label", style = MaterialTheme.typography.labelSmall,
                        color = if (i + 1 == number) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BudgetStep(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanPill("Trợ lý chi tiêu")
        Text("Ngân sách của bạn là bao nhiêu?", style = MaterialTheme.typography.headlineMedium)
        Text("Nhập số tiền dự định chi để theo dõi kế hoạch mua sắm của bạn.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanIcon(R.drawable.ic_plan_wallet)
                Text("NGÂN SÁCH DỰ KIẾN", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                PlanPill("VND")
            }
            OutlinedTextField(state.draft.budget, { onAction(PlanAction.Budget(it)) }, Modifier.fillMaxWidth().semantics {
                contentDescription = "Ngân sách dự kiến"
                state.errors["budget"]?.let { error(it) }
            },
                enabled = !state.frozen, singleLine = true, placeholder = { Text("500000", style = MaterialTheme.typography.headlineLarge) },
                suffix = { Text("₫", color = MaterialTheme.colorScheme.primary) }, textStyle = MaterialTheme.typography.headlineLarge.copy(color = MaterialTheme.colorScheme.primary),
                isError = state.errors["budget"] != null, shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(PlanAction.Continue) }))
            Text("Nhập số tiền không có dấu phân cách hàng nghìn.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("CHỌN NHANH NGÂN SÁCH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val presets = listOf("300000" to "Gọn nhẹ", "500000" to "Vừa phải", "1000000" to "Linh hoạt", "1500000" to "Rộng rãi")
    for (row in presets.chunked(if (LocalDensity.current.fontScale > 1.3f) 1 else 2)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((amount, label) in row) Surface(Modifier.weight(1f).toggleable(state.draft.budget.toBigDecimalOrNull()?.compareTo(amount.toBigDecimal()) == 0,
            enabled = !state.frozen, role = Role.RadioButton, onValueChange = { onAction(PlanAction.Budget(amount)) }),
            shape = RoundedCornerShape(16.dp), color = if (state.draft.budget == amount) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(planMoney(amount), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    PlanNotice("Ngân sách là mức chi dự kiến. Khi vượt ngân sách, bạn sẽ được cảnh báo để tự quyết định.")
}

@Composable
private fun StoreStep(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    Intro("Bạn muốn mua ở đâu?", "Chọn tối đa ${state.maxStores ?: 3} nơi mua. Chuỗi có nhiều chi nhánh cho phép chọn đúng nơi bạn hay ghé.")
    PlanSummary(state, onEdit = { onAction(PlanAction.Back) })
    SearchField(state.retailerQuery, "Tìm chuỗi cửa hàng", !state.frozen) { onAction(PlanAction.RetailerQuery(it)) }
    ReferenceStatus(state, onAction)
    Text("Cửa hàng & siêu thị", style = MaterialTheme.typography.titleMedium)
    val choices = state.retailers.filter { (it.status == "ACTIVE" || state.draft.selections.any { s -> s.retailerId == it.id }) && it.name.contains(state.retailerQuery.trim(), ignoreCase = true) }
    if (!state.referencesLoading && state.referenceError == null && choices.isEmpty()) PlanNotice(if (state.retailerQuery.isBlank())
        "Chưa có chuỗi cửa hàng khả dụng. Hãy thử tải lại sau." else "Không tìm thấy chuỗi phù hợp. Thử tên khác.")
    for (retailer in choices) RetailerCard(state, retailer, onAction)
    val own = state.branches.filter { it.private }
    if (own.isNotEmpty()) {
        Text("Cửa hàng của bạn", style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                own.forEach { store -> PlaceOption(store.name, store.address ?: "Cửa hàng riêng của bạn",
                    store.id in state.draft.selections.mapNotNull { it.storeId }, Role.Checkbox, !state.frozen) { onAction(PlanAction.Store(store.id)) } }
            }
        }
    }
}

@Composable
private fun RetailerCard(state: PlanUiState, retailer: PlanReference, onAction: (PlanAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val branches = state.branchesOf(retailer.id)
    val selected = state.draft.selections.filter { it.retailerId == retailer.id }
    val chosen = selected.mapNotNull { it.storeId }
    val active = retailer.status == "ACTIVE"
    val on = selected.isNotEmpty()
    Surface(shape = RoundedCornerShape(20.dp), color = if (on) colors.secondaryContainer else colors.surfaceContainerLowest,
        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) colors.primary else colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(on, enabled = !state.frozen && state.maxStores != null,
                role = Role.Checkbox, onValueChange = { onAction(PlanAction.Retailer(retailer.id)) }).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(if (on) colors.surface else colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    PlanIcon(R.drawable.ic_plan_store)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(retailer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        !active -> "Không còn khả dụng · có thể bỏ chọn"
                        branches.isEmpty() -> "Giá áp dụng chung toàn chuỗi"
                        chosen.isNotEmpty() -> "Đã chọn ${chosen.size}/${branches.size} chi nhánh"
                        on -> "Tất cả ${branches.size} chi nhánh"
                        else -> "${branches.size} chi nhánh · chọn để xem"
                    }, style = MaterialTheme.typography.bodySmall, color = if (active) colors.onSurfaceVariant else colors.error)
                }
                Checkbox(on, null)
            }
            if (on && active && branches.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("Mua ở chi nhánh nào?", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    PlaceOption("Tất cả chi nhánh", "Xem sản phẩm chung của ${retailer.name}", chosen.isEmpty(), Role.RadioButton, !state.frozen) {
                        onAction(PlanAction.Chain(retailer.id))
                    }
                    branches.forEach { store ->
                        val picked = store.id in chosen
                        // Unticking the last branch falls back to the whole chain instead of dropping the retailer.
                        PlaceOption(branchLabel(store.name, retailer.name), store.address ?: "Chi nhánh ${store.name}", picked, Role.Checkbox, !state.frozen) {
                            onAction(if (picked && chosen.size == 1) PlanAction.Chain(retailer.id) else PlanAction.Store(store.id))
                        }
                    }
                }
            }
        }
    }
}

/** "Emart Phan Văn Trị" under the Emart card reads as "Phan Văn Trị". */
private fun branchLabel(name: String, retailer: String): String =
    if (name.startsWith(retailer, ignoreCase = true)) name.substring(retailer.length).trim().ifBlank { name } else name

@Composable
private fun PlaceOption(title: String, detail: String, checked: Boolean, role: Role, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, enabled = enabled, role = role, onValueChange = { onClick() }).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (role == Role.RadioButton) RadioButton(checked, null, enabled = enabled) else Checkbox(checked, null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(200)) }, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true,
        shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(painterResource(R.drawable.ic_catalog_search), null, Modifier.size(20.dp)) },
        trailingIcon = if (value.isEmpty()) null else ({ TextButton({ onChange("") }, enabled = enabled) { Text("Xóa") } }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
}

private const val CATEGORY_PREVIEW = 12

@Composable
private fun CategoryStep(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Intro("Bạn muốn mua gì?", "Chạm để chọn các loại hàng cần mua ở từng nơi. Bạn sẽ tự chọn sản phẩm cụ thể ở bước sau.")
    PlanSummary(state, onEdit = { onAction(PlanAction.Back) }, includePlaces = true)
    ReferenceStatus(state, onAction)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Loại hàng", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        PlanPill("${state.draft.categoryIds.size} đã chọn")
    }
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectedRetailers = state.draft.selections.mapNotNull { it.retailerId }.distinct()
    val availableCategories = state.categories.filter { category ->
        category.id in state.draft.categoryIds || (category.status == "ACTIVE" && (category.retailerIds.isEmpty() || category.retailerIds.any { it in selectedRetailers }))
    }
    SearchField(categoryQuery, "Tìm loại hàng", !state.frozen) { categoryQuery = it }
    val matching = availableCategories.filter { categoryQuery.isBlank() || it.name.contains(categoryQuery.trim(), ignoreCase = true) }
    if (matching.isEmpty() && !state.referencesLoading && state.referenceError == null) PlanNotice(if (categoryQuery.isBlank())
        "Chưa có loại hàng khả dụng. Hãy thử tải lại, lựa chọn của bạn vẫn được giữ." else "Không có loại hàng phù hợp. Thử từ khóa khác.")
    // Each provider category belongs to one retailer, so grouping by the chosen places avoids look-alike duplicates.
    val groups = matching.groupBy { c -> c.retailerIds.firstOrNull { it in selectedRetailers } ?: if (c.retailerIds.isEmpty()) "" else "other" }
    for (key in selectedRetailers + listOf("", "other")) {
        val items = groups[key] ?: continue
        val title = when (key) { "" -> "Loại hàng chung"; "other" -> "Đã chọn trước đó"; else -> state.retailers.firstOrNull { it.id == key }?.name ?: "Nơi mua đã chọn" }
        val open = categoryQuery.isNotBlank() || key in expanded
        // Keep source order so a tapped chip never jumps; selections past the preview stay visible.
        val shown = if (open) items else items.filterIndexed { i, c -> i < CATEGORY_PREVIEW || c.id in state.draft.categoryIds }
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanIcon(if (key == "" || key == "other") R.drawable.ic_plan_category else R.drawable.ic_plan_store, Modifier.size(20.dp))
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${items.count { it.id in state.draft.categoryIds }}/${items.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { category ->
                        val selected = category.id in state.draft.categoryIds
                        FilterChip(selected, { onAction(PlanAction.Category(category.id)) }, enabled = !state.frozen,
                            label = { Text(category.name, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = if (selected) ({ Icon(painterResource(R.drawable.ic_auth_check), null, Modifier.size(18.dp)) }) else null,
                            shape = RoundedCornerShape(12.dp),
                            colors = if (category.status == "ACTIVE") FilterChipDefaults.filterChipColors()
                                else FilterChipDefaults.filterChipColors(selectedContainerColor = colors.errorContainer, selectedLabelColor = colors.onErrorContainer))
                    }
                }
                if (items.any { it.status != "ACTIVE" && it.id in state.draft.categoryIds })
                    Text("Loại hàng tô đỏ không còn khả dụng, hãy bỏ chọn.", style = MaterialTheme.typography.bodySmall, color = colors.error)
                if (categoryQuery.isBlank() && items.size > CATEGORY_PREVIEW) TextButton({ expanded = if (open) expanded - key else expanded + key }, enabled = !state.frozen) {
                    Text(if (open) "Thu gọn" else "Xem thêm ${items.size - CATEGORY_PREVIEW} loại hàng")
                }
            }
        }
    }
    PlanNotice("Bản nháp được lưu trước. Danh mục sản phẩm sẽ hiển thị khi có dữ liệu khả dụng; hệ thống không tự thêm món vào kế hoạch.")
}

@Composable
private fun PlanDetail(state: PlanUiState) {
    if (state.opening) { CircularProgressIndicator(); Text("Đang mở kế hoạch…"); return }
    val view = state.saved ?: return
    val plan = view.plan
    PlanPill(planStatus(plan.status))
    Text(planTitle(plan), style = MaterialTheme.typography.headlineMedium)
    PlanSummary(state, includePlaces = true)
    Text("Nhóm hàng", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { plan.categoryIds.forEach { id ->
        PlanPill(state.categories.firstOrNull { it.id == id }?.name ?: "Nhóm hàng đã lưu")
    } }
    if (view.items.isNotEmpty()) {
        Text("${view.items.size} sản phẩm đã chọn", style = MaterialTheme.typography.titleMedium)
        Text("Tổng đã biết: ${planMoney(plan.budgetSummary.selectedTotal.amount.toPlainString(), plan.budget.currency)}", style = MaterialTheme.typography.bodyMedium)
        if (!plan.budgetSummary.complete) PlanNotice("Một số sản phẩm chưa có giá. Tổng tiền hiện chưa đầy đủ.")
    }
    view.warnings.forEach { PlanNotice(it.message) }
    ReferenceDetailError(state)
    PlanNotice("Kế hoạch đã được lưu. Mở danh mục để xem sản phẩm theo nơi mua và nhóm hàng đã chọn.")
}

@Composable
private fun PlanSummary(state: PlanUiState, onEdit: (() -> Unit)? = null, includePlaces: Boolean = false) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetSummaryLabel(state)
                if (includePlaces) PlaceSummaryLabel(state)
                if (onEdit != null) TextButton(onEdit, enabled = !state.frozen) { Text("Chỉnh sửa") }
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { BudgetSummaryLabel(state) }
            if (includePlaces) Column(Modifier.weight(1f)) { PlaceSummaryLabel(state) }
            if (onEdit != null) TextButton(onEdit, enabled = !state.frozen, contentPadding = PaddingValues(4.dp)) {
                Text("Chỉnh sửa", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable private fun BudgetSummaryLabel(state: PlanUiState) {
    Text("Ngân sách dự kiến", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(planMoney(state.draft.budget, state.draft.currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
@Composable private fun PlaceSummaryLabel(state: PlanUiState) {
    Text("Nơi mua đã chọn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.draft.selections.forEach { selection -> Text("${state.placeName(selection)} · ${if (selection.storeId == null) "Cả chuỗi" else "Chi nhánh cụ thể"}",
        style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ReferenceStatus(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    if (state.referencesLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp)); Text("Đang tải dữ liệu…", style = MaterialTheme.typography.bodyMedium)
    }
    state.referenceError?.let { PlanNotice(it, error = true); OutlinedButton({ onAction(PlanAction.RetryReferences) }) { Text("Tải lại dữ liệu") } }
}
@Composable private fun ReferenceDetailError(state: PlanUiState) {
    state.referenceError?.let { PlanNotice("Chưa tải được tên nơi mua hoặc nhóm hàng. Thông tin kế hoạch vẫn được giữ.") }
}
@Composable private fun Intro(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PlanPill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
@Composable private fun PlanNotice(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
@Composable private fun ErrorText(text: String) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
@Composable private fun PlanIcon(@DrawableRes id: Int, modifier: Modifier = Modifier) {
    Icon(painterResource(id), null, modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
fun PlanHomeCards(state: PlanUiState, onAction: (PlanAction) -> Unit, modifier: Modifier = Modifier, query: String = "") {
    val plans = state.plans.filter { query.isBlank() || "${planTitle(it)} ${planMoney(it.budget.amount.toPlainString())} ${planStatus(it.status)}".contains(query, ignoreCase = true) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.plansLoading) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Đang tải kế hoạch…") }
        state.plansError?.let { PlanNotice(it, error = true); TextButton({ onAction(PlanAction.ReloadPlans) }) { Text("Thử tải lại kế hoạch") } }
        if (plans.isEmpty() && !state.plansLoading && state.plansError == null) PlanNotice(if (query.isBlank()) "Chưa có kế hoạch mua sắm. Bắt đầu một kế hoạch để sắp xếp nhu cầu và ngân sách." else "Không có kế hoạch phù hợp với tìm kiếm.")
        plans.forEach { plan ->
            Surface(onClick = { onAction(PlanAction.Open(plan.id)) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(planTitle(plan), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        PlanPill(planStatus(plan.status))
                    }
                    Text(planMoney(plan.budget.amount.toPlainString(), plan.budget.currency), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${plan.selections.size} nơi mua · ${plan.categoryIds.size} nhóm hàng", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.nextCursor != null) OutlinedButton({ onAction(PlanAction.MorePlans) }, enabled = !state.plansLoading) { Text("Xem thêm kế hoạch") }
    }
}
