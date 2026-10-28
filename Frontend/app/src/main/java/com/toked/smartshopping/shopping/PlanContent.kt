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
                        val inputError = if (state.branchesOpen) null else when (state.step) {
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
    if (state.branchesOpen) BranchDialog(state, onAction)
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
    Intro("Bạn muốn mua ở đâu?", "Chọn 1–${state.maxStores ?: 3} nơi mua. Bạn có thể chọn cả chuỗi hoặc một chi nhánh cụ thể.")
    PlanSummary(state, onEdit = { onAction(PlanAction.Back) })
    PlanNotice("Chọn cả chuỗi không cần quyền vị trí.")
    OutlinedTextField(state.retailerQuery, { onAction(PlanAction.RetailerQuery(it)) }, Modifier.fillMaxWidth(),
        label = { Text("Tìm chuỗi cửa hàng") }, enabled = !state.frozen, singleLine = true, shape = RoundedCornerShape(16.dp))
    ReferenceStatus(state, onAction)
    Text("Cửa hàng & siêu thị", style = MaterialTheme.typography.titleMedium)
    val choices = state.retailers.filter { (it.status == "ACTIVE" || state.draft.selections.any { s -> s.retailerId == it.id }) && it.name.contains(state.retailerQuery.trim(), ignoreCase = true) }
    if (!state.referencesLoading && state.referenceError == null && choices.isEmpty()) PlanNotice(if (state.retailerQuery.isBlank())
        "Chưa có chuỗi cửa hàng khả dụng. Hãy thử lại sau hoặc chọn cửa hàng đã lưu khác." else "Không tìm thấy chuỗi phù hợp. Thử tên khác hoặc chọn cửa hàng đã lưu.")
    for (retailer in choices) {
        val selected = state.draft.selections.filter { it.retailerId == retailer.id }
        Surface(shape = RoundedCornerShape(20.dp), color = if (selected.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, if (selected.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().toggleable(selected.isNotEmpty(), enabled = !state.frozen && state.maxStores != null,
                    role = Role.Checkbox, onValueChange = { onAction(PlanAction.Retailer(retailer.id)) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlanIcon(R.drawable.ic_plan_store)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(retailer.name, style = MaterialTheme.typography.titleMedium)
                        Text(when { retailer.status != "ACTIVE" -> "Không còn khả dụng · có thể bỏ chọn"
                            selected.any { it.storeId != null } -> "${selected.count { it.storeId != null }} chi nhánh đã chọn"
                            else -> "Cả chuỗi · chưa chọn chi nhánh" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(selected.isNotEmpty(), null)
                }
                if (selected.isNotEmpty()) TextButton({ onAction(PlanAction.Branches(retailer.id)) }, enabled = !state.frozen && retailer.status == "ACTIVE",
                    modifier = Modifier.padding(start = 44.dp)) { Text("Chọn chi nhánh") }
            }
        }
    }
    OutlinedButton({ onAction(PlanAction.Branches(null)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.frozen) { Text("Chọn cửa hàng đã lưu khác") }
    if (state.draft.selections.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Đã chọn", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.draft.selections.forEach { selection ->
            InputChip(selected = true, onClick = { onAction(selection.storeId?.let { PlanAction.Store(it) } ?: PlanAction.Retailer(selection.retailerId!!)) },
                enabled = !state.frozen, label = { Text("${state.placeName(selection)} · Bỏ chọn") })
        } }
    }
}

@Composable
private fun CategoryStep(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    Intro("Bạn muốn mua gì?", "Chọn một hoặc nhiều nhóm hàng cho kế hoạch. Bạn sẽ tự quyết định các sản phẩm cần mua.")
    PlanSummary(state, onEdit = { onAction(PlanAction.Back) }, includePlaces = true)
    ReferenceStatus(state, onAction)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Nhóm hàng", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        PlanPill("${state.draft.categoryIds.size} đã chọn")
    }
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var categoryLimit by rememberSaveable { mutableStateOf(60) }
    val selectedRetailers=state.draft.selections.mapNotNull {it.retailerId}.toSet()
    val availableCategories = state.categories.filter { category ->
        category.id in state.draft.categoryIds || (category.status=="ACTIVE" && (category.retailerIds.isEmpty() || category.retailerIds.any {it in selectedRetailers}))
    }
    OutlinedTextField(categoryQuery,{categoryQuery=it.take(200);categoryLimit=60},Modifier.fillMaxWidth(),singleLine=true,
        label={Text("Tìm nhóm hàng")},enabled=!state.frozen)
    val matchingCategories=availableCategories.filter {categoryQuery.isBlank() || it.name.contains(categoryQuery,ignoreCase=true)}
    val categories = matchingCategories.take(categoryLimit)
    if (categories.isEmpty() && !state.referencesLoading && state.referenceError == null) PlanNotice("Chưa có nhóm hàng khả dụng. Hãy thử tải lại, lựa chọn của bạn vẫn được giữ.")
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    for (row in categories.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (category in row) {
            val selected = category.id in state.draft.categoryIds
            Surface(Modifier.weight(1f).toggleable(selected, enabled = !state.frozen, role = Role.Checkbox,
                onValueChange = { onAction(PlanAction.Category(category.id)) }), shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp).heightIn(min = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PlanIcon(R.drawable.ic_plan_category, Modifier.weight(1f).wrapContentWidth(Alignment.Start))
                        Checkbox(selected, null)
                    }
                    Text(category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (category.status != "ACTIVE") Text("Không còn khả dụng · bỏ chọn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (row.size < columns) Spacer(Modifier.weight(1f))
    }
    if(matchingCategories.size>categoryLimit) TextButton({categoryLimit+=60},enabled=!state.frozen) {Text("Thêm nhóm hàng (${categories.size}/${matchingCategories.size})")}
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
private fun BranchDialog(state: PlanUiState, onAction: (PlanAction) -> Unit) {
    AlertDialog(onDismissRequest = { onAction(PlanAction.CloseBranches) }, title = { Text("Chọn cửa hàng cụ thể") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Chi nhánh thay cho lựa chọn cả chuỗi tương ứng. Mỗi cửa hàng được tính là một nơi mua.")
                OutlinedTextField(state.branchQuery, { onAction(PlanAction.BranchQuery(it)) }, label = { Text("Tên cửa hàng hoặc khu vực") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onAction(PlanAction.SearchBranches) }))
                OutlinedButton({ onAction(PlanAction.SearchBranches) }, enabled = !state.branchesLoading) { Text("Tìm cửa hàng") }
                if (state.branchesLoading) CircularProgressIndicator(Modifier.size(24.dp))
                state.branchError?.let { ErrorText(it); TextButton({ onAction(PlanAction.SearchBranches) }) { Text("Thử lại") } }
                if (state.branchSearchPending) Text("Nhấn Tìm cửa hàng để xem kết quả cho tên hoặc khu vực vừa nhập.")
                if (state.branches.isEmpty() && !state.branchesLoading && state.branchError == null && !state.branchSearchPending)
                    Text("Chưa có cửa hàng phù hợp trong dữ liệu đã lưu. Bạn vẫn có thể chọn cả chuỗi.")
                state.branches.forEach { store ->
                    Row(Modifier.fillMaxWidth().toggleable(state.draft.selections.any { it.storeId == store.id }, role = Role.Checkbox,
                        onValueChange = { onAction(PlanAction.Store(store.id)) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(store.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (store.private) "Cửa hàng riêng của bạn" else "Chi nhánh cụ thể", style = MaterialTheme.typography.bodySmall)
                            store.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Checkbox(state.draft.selections.any { it.storeId == store.id }, null)
                    }
                }
                if (state.branchCursor != null) TextButton({ onAction(PlanAction.MoreBranches) }, enabled = !state.branchesLoading) { Text("Xem thêm cửa hàng") }
                state.branchWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.attribution.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }, confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.errors["selections"]?.let { ErrorText(it) }
                TextButton({ onAction(PlanAction.CloseBranches) }) { Text("Xong") }
            }
        })
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
