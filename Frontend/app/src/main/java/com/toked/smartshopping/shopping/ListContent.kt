@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.toked.smartshopping.shopping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toked.smartshopping.R
import java.math.BigDecimal
import java.math.RoundingMode

/** Stitch "Danh Sách Đã Chọn", "Kiểm Tra Kế Hoạch" and "Kế hoạch đã chốt". Totals are always server values. */
@Composable
fun ListContent(state: ListUiState, references: PlanUiState, onAction: (ListAction) -> Unit, onPlanAction: (PlanAction) -> Unit,
    modifier: Modifier = Modifier) {
    BackHandler { onPlanAction(PlanAction.Back) }
    val view = state.view ?: return
    val shown = if (state.screen == ListScreen.REVIEW) state.review?.view else view
    val title = when (state.screen) { ListScreen.LIST -> "Danh sách đã chọn"; ListScreen.REVIEW -> "Kiểm tra kế hoạch"; ListScreen.FINALIZED -> "Kế hoạch đã chốt" }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onPlanAction(PlanAction.Back) }, enabled = !state.busy) { Icon(painterResource(R.drawable.ic_auth_arrow_back), "Quay lại") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (state.screen == ListScreen.REVIEW) "Hãy kiểm tra lại trước khi bắt đầu mua sắm" else "${view.items.size} sản phẩm · ${storeCount(view)} nơi mua",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("list-content"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { item { ListNotice(it, error = true) } }
                state.message?.let { item { ListNotice(it) } }
                when (state.screen) {
                    ListScreen.LIST -> selectedList(view, references, state, onAction, onPlanAction)
                    ListScreen.REVIEW -> if (shown == null) item {
                        // A failed review must not look like it is still loading; offer a retry.
                        if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp)); Text("Đang kiểm tra giá và tổng tiền…")
                        } else OutlinedButton({ onAction(ListAction.Review) }, Modifier.fillMaxWidth()) { Text("Thử kiểm tra lại") }
                    } else review(state.review!!, view, references, state, onAction)
                    ListScreen.FINALIZED -> finalized(view, references)
                }
            }
            Surface(shadowElevation = 3.dp, color = colors.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (state.screen) {
                        ListScreen.LIST -> {
                            TotalLine(view)
                            Button({ onAction(ListAction.Review) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !state.busy && view.items.isNotEmpty(),
                                shape = RoundedCornerShape(16.dp)) {
                                Text("Chốt kế hoạch"); Spacer(Modifier.width(8.dp)); Icon(painterResource(R.drawable.ic_auth_arrow_forward), null, Modifier.size(20.dp))
                            }
                        }
                        ListScreen.REVIEW -> {
                            val review = state.review
                            if (review != null) TotalLine(review.view)
                            val needsConsent = review != null && review.priceChangedItemIds.isNotEmpty() && !state.confirmPriceChanges
                            Button({ onAction(ListAction.Finalize) }, Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                enabled = !state.busy && review != null && review.canFinalize && !needsConsent, shape = RoundedCornerShape(16.dp)) {
                                if (state.busy && review != null) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary); Spacer(Modifier.width(8.dp)) }
                                Icon(painterResource(R.drawable.ic_auth_check), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Xác nhận chốt")
                            }
                            TextButton({ onAction(ListAction.BackToList) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Quay lại chỉnh sửa danh sách") }
                        }
                        ListScreen.FINALIZED -> {
                            if (view.plan.status == PlanState.FINALIZED) OutlinedButton({ onAction(ListAction.Reopen) }, Modifier.fillMaxWidth(), enabled = !state.busy) {
                                Icon(painterResource(R.drawable.ic_auth_edit), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sửa kế hoạch")
                            }
                            Button({ onPlanAction(PlanAction.Home) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !state.busy, shape = RoundedCornerShape(16.dp)) { Text("Về trang chủ") }
                        }
                    }
                }
            }
        }
    }
    state.confirmRemove?.let { item ->
        AlertDialog(onDismissRequest = { onAction(ListAction.CancelRemove) },
            icon = { Icon(painterResource(R.drawable.ic_auth_shield), null, tint = colors.error) },
            title = { Text("Món hàng thiết yếu") },
            text = { Text("\"${itemName(item)}\" được đánh dấu Thiết yếu. Bạn có chắc chắn muốn bỏ khỏi kế hoạch?") },
            dismissButton = { TextButton({ onAction(ListAction.CancelRemove) }) { Text("Giữ lại") } },
            confirmButton = { TextButton({ onAction(ListAction.ConfirmRemove) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("Xác nhận bỏ") } })
    }
}

private fun LazyListScope.selectedList(view: ShoppingPlanView, references: PlanUiState, state: ListUiState, onAction: (ListAction) -> Unit, onPlanAction: (PlanAction) -> Unit) {
    item { BudgetCard(view.plan, view.items.size) }
    if (view.items.isEmpty()) item { ListNotice("Danh sách đang trống. Thêm sản phẩm từ danh mục để lập kế hoạch.") }
    else item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sản phẩm đã chọn", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${view.items.size} món", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    groups(view.items).forEach { (retailer, groupItems) ->
        item(key = "group-$retailer") { GroupHeader(retailerLabel(retailer, references), groupItems, view.plan.budget.currency) }
        items(groupItems, key = { it.id }) { item -> EditableItem(item, retailerLabel(retailer, references), state.busy, onAction) }
    }
    item {
        OutlinedButton({ onPlanAction(PlanAction.AddProducts) }, Modifier.fillMaxWidth(), enabled = !state.busy, shape = RoundedCornerShape(14.dp)) {
            Text("+ Thêm sản phẩm vào kế hoạch")
        }
    }
}

private fun LazyListScope.review(review: FinalizationReview, before: ShoppingPlanView, references: PlanUiState, state: ListUiState, onAction: (ListAction) -> Unit) {
    val view = review.view
    item {
        StatusChip(if (review.canFinalize) "Sẵn sàng để chốt" else "Chưa thể chốt", ok = review.canFinalize)
    }
    item { SummaryCard(view, review.storeCount, "Kế hoạch của bạn") }
    item { Text("Mua tại (${review.storeCount} nơi mua)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    groups(view.items).forEach { (retailer, groupItems) ->
        item(key = "review-$retailer") { GroupHeader(retailerLabel(retailer, references), groupItems, view.plan.budget.currency) }
        items(groupItems, key = { "review-${it.id}" }) { item ->
            val old = before.items.firstOrNull { it.id == item.id }?.selectedPriceSnapshot?.price
            ReadOnlyItem(item, if (item.id in review.priceChangedItemIds) old else null)
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Thông tin & tính hợp lệ của giá", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            view.warnings.forEach { ListNotice(warningText(it, view.plan.budgetSummary), error = it.code in setOf("STORE_LIMIT_EXCEEDED", "PRICE_CHANGED")) }
            ListNotice("Giá được ghi nhận ở cấp chuỗi và có thể chênh lệch theo từng chi nhánh. Sau khi chốt, giá này được lưu cho kế hoạch.")
        }
    }
    if (review.priceChangedItemIds.isNotEmpty()) item {
        Row(Modifier.fillMaxWidth().toggleable(state.confirmPriceChanges, enabled = !state.busy, role = Role.Checkbox) { onAction(ListAction.ConfirmPrices(it)) }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.confirmPriceChanges, null)
            Spacer(Modifier.width(8.dp))
            Text("Tôi đồng ý dùng giá mới cho ${review.priceChangedItemIds.size} món đã đổi giá.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun LazyListScope.finalized(view: ShoppingPlanView, references: PlanUiState) {
    item {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_auth_check), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(if (view.plan.status == PlanState.FINALIZED) "Kế hoạch đã chốt!" else planStatus(view.plan.status), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Danh sách mua sắm và mức giá tham chiếu đã được lưu cho kế hoạch này.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    item { SummaryCard(view, storeCount(view), "Snapshot đã chốt") }
    item { Text("Mua tại (${storeCount(view)} nơi mua)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    groups(view.items).forEach { (retailer, groupItems) ->
        item(key = "final-$retailer") { GroupHeader(retailerLabel(retailer, references), groupItems, view.plan.budget.currency) }
        items(groupItems, key = { "final-${it.id}" }) { ReadOnlyItem(it, null) }
    }
    item {
        ListNotice("Giá đã được lưu tại thời điểm chốt nên danh sách không tự thay đổi theo dữ liệu mới. Giá thực tế tại chi nhánh có thể chênh lệch nhẹ.")
    }
    item { ListNotice("Chọn chi nhánh và tạo lộ trình sẽ có ở bước tiếp theo khi tính năng bản đồ được kết nối.") }
}

@Composable private fun BudgetCard(plan: ShoppingPlan, count: Int) {
    val colors = MaterialTheme.colorScheme; val s = plan.budgetSummary; val over = s.remainingBudget.amount.signum() < 0
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_plan_wallet), null, Modifier.size(22.dp), tint = colors.primary); Spacer(Modifier.width(8.dp))
                Text("Tiến trình ngân sách", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                StatusChip(if (over) "Vượt ngân sách" else "Trong tầm kiểm soát", ok = !over)
            }
            MoneyRow("Kế hoạch", plan.budget)
            MoneyRow("Đã chọn ($count)", s.selectedTotal)
            MoneyRow(if (over) "Vượt" else "Còn lại", if (over) s.overBudgetAmount else s.remainingBudget, emphasis = if (over) colors.error else colors.primary)
            LinearProgressIndicator(progress = { used(plan).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = if (over) colors.error else colors.primary)
            Text("Đã dùng ${percent(plan)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!s.complete) Text("Tổng chưa đầy đủ: ${s.missingPriceItemIds.size} món chưa có giá (không tính là 0 ₫).", style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

@Composable private fun SummaryCard(view: ShoppingPlanView, stores: Int, title: String) {
    val colors = MaterialTheme.colorScheme; val plan = view.plan; val s = plan.budgetSummary; val over = s.remainingBudget.amount.signum() < 0
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${view.items.size} sản phẩm · $stores nơi mua", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            MoneyRow("Ngân sách", plan.budget)
            MoneyRow("Tổng dự kiến", s.selectedTotal, emphasis = colors.onSurface)
            MoneyRow(if (over) "Vượt ngân sách" else "Còn lại", if (over) s.overBudgetAmount else s.remainingBudget, emphasis = if (over) colors.error else colors.primary)
            Text("Đã dùng ${percent(plan)} ngân sách", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (over) Text("Vượt ngân sách chỉ là cảnh báo; bạn vẫn có thể chốt.", style = MaterialTheme.typography.bodySmall, color = colors.error)
            if (!s.complete) Text("${s.missingPriceItemIds.size} món chưa có giá nên tổng chưa đầy đủ.", style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

@Composable private fun GroupHeader(name: String, items: List<ShoppingItem>, currency: String) {
    val known = items.mapNotNull { it.subtotal?.amount }
    Column(Modifier.padding(top = 4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        // Display-only group label; plan totals are the server's budget summary.
        Text("${items.size} sản phẩm" + if (known.isEmpty()) "" else " · ${planMoney(known.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString(), currency)}" +
            if (known.size < items.size) " (thiếu giá)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun EditableItem(item: ShoppingItem, retailer: String, busy: Boolean, onAction: (ListAction) -> Unit) {
    val colors = MaterialTheme.colorScheme; val name = itemName(item)
    val step = item.selectedPriceSnapshot?.product?.quantityStep?.decimal() ?: BigDecimal.ONE
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(retailer, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton({ onAction(ListAction.Remove(item.id)) }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_delete), "Bỏ $name khỏi danh sách", Modifier.size(22.dp), tint = colors.error)
                }
            }
            PriceText(item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityMenu(item, name, busy, onAction)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ onAction(ListAction.Quantity(item.id, false)) }, enabled = !busy && item.quantity.decimal() > step) {
                            Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Giảm số lượng $name" })
                        }
                        Text(item.quantity.value, Modifier.widthIn(min = 28.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                        IconButton({ onAction(ListAction.Quantity(item.id, true)) }, enabled = !busy) {
                            Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Tăng số lượng $name" })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PriorityMenu(item: ShoppingItem, name: String, busy: Boolean, onAction: (ListAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip({ open = true }, label = { Text(priorityLabel(item.priority) + " ▾") }, enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "Mức ưu tiên của $name: ${priorityLabel(item.priority)}" })
        DropdownMenu(open, { open = false }) {
            for (value in listOf("ESSENTIAL", "RECOMMENDED", "OPTIONAL")) DropdownMenuItem({ Text(priorityLabel(value)) }, onClick = {
                open = false; if (value != item.priority) onAction(ListAction.Priority(item.id, value))
            })
        }
    }
}

@Composable private fun ReadOnlyItem(item: ShoppingItem, oldPrice: Money?) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(itemName(item), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(priorityLabel(item.priority), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                val price = item.selectedPriceSnapshot?.price
                Text(if (price == null) "${item.quantity.value} ${item.unit} · chưa có giá" else "${item.quantity.value} × ${planMoney(price.amount.toPlainString(), price.currency)}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                oldPrice?.let { Text("Giá cũ ${planMoney(it.amount.toPlainString(), it.currency)}", style = MaterialTheme.typography.bodySmall,
                    color = colors.error, textDecoration = TextDecoration.LineThrough) }
            }
            Text(item.subtotal?.let { planMoney(it.amount.toPlainString(), it.currency) } ?: "Thiếu giá", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = if (item.subtotal == null) colors.error else colors.onSurface)
        }
    }
}

@Composable private fun PriceText(item: ShoppingItem) {
    val price = item.selectedPriceSnapshot?.price
    if (price == null) { Text("Chưa có giá", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error); return }
    val unit = item.selectedPriceSnapshot?.product?.packageLabel ?: item.unit
    Text("${planMoney(price.amount.toPlainString(), price.currency)} / $unit" + (item.subtotal?.takeIf { item.quantity.decimal().compareTo(BigDecimal.ONE) != 0 }
        ?.let { " = ${planMoney(it.amount.toPlainString(), it.currency)}" } ?: ""), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable private fun TotalLine(view: ShoppingPlanView) {
    val s = view.plan.budgetSummary; val over = s.remainingBudget.amount.signum() < 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${view.items.size} sản phẩm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(planMoney(s.selectedTotal.amount.toPlainString(), s.selectedTotal.currency) + if (s.complete) "" else " · thiếu giá",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(if (over) "Vượt ${planMoney(s.overBudgetAmount.amount.toPlainString(), s.overBudgetAmount.currency)}"
            else "Còn dư ${planMoney(s.remainingBudget.amount.toPlainString(), s.remainingBudget.currency)}",
            style = MaterialTheme.typography.labelLarge, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun MoneyRow(label: String, value: Money, emphasis: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(planMoney(value.amount.toPlainString(), value.currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = emphasis)
    }
}

@Composable private fun StatusChip(text: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = if (ok) colors.secondaryContainer else colors.errorContainer) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (ok) colors.primary else colors.onErrorContainer)
    }
}

@Composable private fun ListNotice(text: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (error) colors.errorContainer else colors.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = if (error) colors.onErrorContainer else colors.onSecondaryContainer)
    }
}

/** Items grouped by their offer's retailer; custom items (no offer) come last. */
internal fun groups(items: List<ShoppingItem>): List<Pair<String?, List<ShoppingItem>>> =
    items.groupBy { it.selectedPriceSnapshot?.retailerId }.toList().sortedBy { it.first == null }
internal fun storeCount(view: ShoppingPlanView) = view.items.mapNotNull { it.selectedPriceSnapshot?.let { o -> o.storeId ?: o.retailerId } }.distinct().size
internal fun itemName(item: ShoppingItem) = item.selectedPriceSnapshot?.product?.name ?: item.customName ?: "Sản phẩm"
internal fun priorityLabel(value: String) = when (value) { "ESSENTIAL" -> "Thiết yếu"; "OPTIONAL" -> "Tùy chọn"; else -> "Nên mua" }
private fun retailerLabel(id: String?, references: PlanUiState) = if (id == null) "Món tự thêm" else references.retailers.firstOrNull { it.id == id }?.name ?: "Nơi mua đã chọn"
private fun used(plan: ShoppingPlan): BigDecimal = plan.budgetSummary.selectedTotal.amount.divide(plan.budget.amount, 4, RoundingMode.HALF_UP)
private fun percent(plan: ShoppingPlan) = used(plan).movePointRight(2).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"
private fun warningText(warning: ShoppingWarning, summary: BudgetSummary) = when (warning.code) {
    "OVER_BUDGET" -> "Tổng dự kiến vượt ngân sách ${planMoney(summary.overBudgetAmount.amount.toPlainString(), summary.overBudgetAmount.currency)}. Bạn vẫn có thể chốt."
    "UNKNOWN_PRICE" -> "${warning.affectedIds.size} món chưa có giá; tổng tiền chưa đầy đủ và món thiếu giá không được tính là 0 ₫."
    else -> warning.message
}
