package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/** Stitch "Danh Sách Đã Chọn" → "Kiểm Tra Kế Hoạch" → "Kế hoạch đã chốt". */
enum class ListScreen { LIST, REVIEW, FINALIZED }
data class ListUiState(
    val screen: ListScreen = ListScreen.LIST, val view: ShoppingPlanView? = null, val busy: Boolean = false,
    val confirmRemove: ShoppingItem? = null, val review: FinalizationReview? = null, val confirmPriceChanges: Boolean = false,
    val error: String? = null, val message: String? = null,
)
sealed interface ListAction {
    data class Quantity(val itemId: String, val increase: Boolean) : ListAction
    data class Priority(val itemId: String, val value: String) : ListAction
    data class Remove(val itemId: String) : ListAction
    data object ConfirmRemove : ListAction
    data object CancelRemove : ListAction
    data object Review : ListAction
    data object BackToList : ListAction
    data class ConfirmPrices(val value: Boolean) : ListAction
    data object Finalize : ListAction
    data object Reopen : ListAction
}

/** Owned by PlanController; every total shown comes from a server PlanView or review, never a client sum. */
class ListController(private val gateway: PlanGateway, private val scope: CoroutineScope,
    private val onPlan: (ShoppingPlanView) -> Unit, private val onSessionEnded: () -> Unit) {
    private val mutable = MutableStateFlow(ListUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun open(view: ShoppingPlanView) {
        job?.cancel()
        mutable.value = ListUiState(if (view.plan.status in editablePlans) ListScreen.LIST else ListScreen.FINALIZED, view)
    }
    fun close() { job?.cancel(); mutable.update { it.copy(busy = false) } }
    fun clear() { job?.cancel(); mutable.value = ListUiState() }
    /** Back inside the flow: Review returns to the List; otherwise the caller leaves the flow. */
    fun back(): Boolean {
        val s = state.value
        if (s.screen != ListScreen.REVIEW || s.busy) return false
        mutable.update { it.copy(screen = ListScreen.LIST, review = null, confirmPriceChanges = false, error = null, message = null) }
        return true
    }
    fun onAction(action: ListAction) {
        when (action) {
            is ListAction.Quantity -> quantity(action.itemId, action.increase)
            is ListAction.Priority -> if (action.value in priorities) change(action.itemId, JSONObject().put("priority", action.value))
            is ListAction.Remove -> remove(action.itemId, confirmed = false)
            ListAction.ConfirmRemove -> state.value.confirmRemove?.let { remove(it.id, confirmed = true) }
            ListAction.CancelRemove -> mutable.update { it.copy(confirmRemove = null) }
            ListAction.Review -> review()
            ListAction.BackToList -> back()
            is ListAction.ConfirmPrices -> mutable.update { it.copy(confirmPriceChanges = action.value) }
            ListAction.Finalize -> finalize()
            ListAction.Reopen -> reopen()
        }
    }
    private fun item(id: String) = state.value.view?.items?.firstOrNull { it.id == id }
    private fun quantity(itemId: String, increase: Boolean) {
        val item = item(itemId) ?: return
        val step = item.selectedPriceSnapshot?.product?.quantityStep?.decimal() ?: java.math.BigDecimal.ONE
        val next = item.quantity.decimal().let { if (increase) it + step else it - step }
        // Quantity 0 is never sent; removing is an explicit action (API-LIST-03).
        if (next.signum() <= 0) return
        change(itemId, JSONObject().put("quantity", next.stripTrailingZeros().toPlainString()))
    }
    private fun change(itemId: String, fields: JSONObject) = mutateList { view ->
        gateway.changeItem(view.plan.id, itemId, PlanMutation(UUID.randomUUID().toString(), fields.put("expected_version", view.plan.version).toString(), view.plan.id))
    }
    private fun remove(itemId: String, confirmed: Boolean) {
        val item = item(itemId) ?: return
        if (item.priority == "ESSENTIAL" && !confirmed) { mutable.update { it.copy(confirmRemove = item) }; return }
        mutable.update { it.copy(confirmRemove = null) }
        mutateList { view -> gateway.removeItem(view.plan.id, itemId, view.plan.version, item.priority == "ESSENTIAL") }
    }
    private fun mutateList(work: suspend (ShoppingPlanView) -> ShoppingPlanView) {
        val s = state.value; val view = s.view ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        job = scope.launch {
            try {
                val next = work(view); currentCoroutineContext().ensureActive()
                mutable.update { it.copy(busy = false, view = next, screen = if (next.plan.status in editablePlans) ListScreen.LIST else ListScreen.FINALIZED) }; onPlan(next)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
    }
    private fun review() {
        val s = state.value; val view = s.view ?: return
        if (s.busy || view.items.isEmpty()) return
        mutable.update { it.copy(screen = ListScreen.REVIEW, review = null, confirmPriceChanges = false, busy = true, error = null, message = null) }
        job = scope.launch {
            try {
                val review = gateway.reviewPlan(view.plan.id, view.plan.version); currentCoroutineContext().ensureActive()
                mutable.update { it.copy(busy = false, review = review) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
    }
    private fun finalize() {
        val s = state.value; val review = s.review ?: return; val planId = s.view?.plan?.id ?: return
        if (s.busy || !review.canFinalize || (review.priceChangedItemIds.isNotEmpty() && !s.confirmPriceChanges)) return
        val body = JSONObject().put("expected_version", review.planVersion).put("review_id", review.id)
            .put("confirm_price_changes", s.confirmPriceChanges).toString()
        mutable.update { it.copy(busy = true, error = null, message = null) }
        job = scope.launch {
            try {
                gateway.finalizePlan(planId, PlanMutation(UUID.randomUUID().toString(), body, planId))
                val view = gateway.plan(planId); currentCoroutineContext().ensureActive()
                mutable.update { it.copy(busy = false, screen = ListScreen.FINALIZED, view = view, review = null) }; onPlan(view)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is AuthFailure.Http && failure.code == "PRICE_CHANGED") {
                    // Prices moved after the review: show a fresh review instead of reusing an old consent.
                    mutable.update { it.copy(busy = false) }; review()
                    mutable.update { it.copy(message = "Giá vừa thay đổi sau khi kiểm tra. Hãy xem lại bản kiểm tra mới rồi chốt.") }
                } else failed(failure)
            }
        }
    }
    private fun reopen() = mutateList { view ->
        gateway.reopenPlan(view.plan.id, PlanMutation(UUID.randomUUID().toString(), JSONObject().put("expected_version", view.plan.version).toString(), view.plan.id))
    }
    private suspend fun failed(failure: Exception) {
        if (failure is AuthFailure.SessionEnded) { clear(); onSessionEnded(); return }
        val code = (failure as? AuthFailure.Http)?.code
        val error = when {
            code == "VERSION_CONFLICT" -> "Kế hoạch vừa thay đổi ở nơi khác. Đã tải lại danh sách."
            code == "INVALID_STATE" -> "Kế hoạch đã chốt hoặc không còn chỉnh sửa được ở trạng thái này."
            code == "EMPTY_LIST" -> "Danh sách đang trống. Thêm sản phẩm trước khi chốt."
            code == "STORE_LIMIT_EXCEEDED" -> "Danh sách dùng nhiều nơi mua hơn giới hạn của kế hoạch."
            code == "PRICE_CONFIRMATION_REQUIRED" -> "Hãy xác nhận giá mới trước khi chốt."
            code == "VALIDATION_ERROR" -> "Số lượng hoặc mức ưu tiên không hợp lệ."
            failure is AuthFailure.Http -> "Chưa thực hiện được (${failure.status} ${failure.code})."
            else -> "Chưa thực hiện được. Kiểm tra kết nối và thử lại."
        }
        mutable.update { it.copy(busy = false, error = error) }
        if (code == "VERSION_CONFLICT") state.value.view?.plan?.id?.let { id ->
            try {
                val view = gateway.plan(id); currentCoroutineContext().ensureActive()
                mutable.update { it.copy(view = view, screen = if (view.plan.status in editablePlans) ListScreen.LIST else ListScreen.FINALIZED, review = null) }
                onPlan(view)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    private companion object { val priorities = setOf("ESSENTIAL", "RECOMMENDED", "OPTIONAL") }
}
