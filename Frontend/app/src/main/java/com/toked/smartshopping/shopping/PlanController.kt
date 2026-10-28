package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class PlanStep { CLOSED, BUDGET, STORES, CATEGORIES, DETAIL }
data class PlanUiState(
    val step: PlanStep = PlanStep.CLOSED, val draft: PlanDraft = PlanDraft(), val saved: ShoppingPlanView? = null,
    val editing: Boolean = false, val maxStores: Int? = null, val retailers: List<PlanReference> = emptyList(),
    val categories: List<PlanReference> = emptyList(), val stores: Map<String, PlanStore> = emptyMap(), val retailerQuery: String = "",
    val referencesLoading: Boolean = false, val referenceError: String? = null,
    val saving: Boolean = false, val opening: Boolean = false, val pending: PlanMutation? = null, val retrySeconds: Int = 0,
    val errors: Map<String, String> = emptyMap(), val message: String? = null, val confirmExit: Boolean = false, val conflict: Boolean = false,
    val plans: List<ShoppingPlan> = emptyList(), val nextCursor: String? = null, val plansLoading: Boolean = false, val plansError: String? = null,
    val branchesOpen: Boolean = false, val branchRetailer: String? = null, val branchQuery: String = "",
    val branches: List<PlanStore> = emptyList(), val branchCursor: String? = null, val branchesLoading: Boolean = false,
    val branchError: String? = null, val branchWarnings: List<String> = emptyList(), val attribution: List<String> = emptyList(), val branchSearchPending: Boolean = false,
) {
    val frozen: Boolean get() = saving || opening || pending != null
}

sealed interface PlanAction {
    data object New : PlanAction
    data object Back : PlanAction
    data object Continue : PlanAction
    data object Save : PlanAction
    data class Budget(val value: String) : PlanAction
    data class Retailer(val id: String) : PlanAction
    data class RetailerQuery(val value: String) : PlanAction
    data class Category(val id: String) : PlanAction
    data class Branches(val retailerId: String?) : PlanAction
    data class BranchQuery(val value: String) : PlanAction
    data object SearchBranches : PlanAction
    data object MoreBranches : PlanAction
    data class Store(val id: String) : PlanAction
    data object CloseBranches : PlanAction
    data object RetryReferences : PlanAction
    data object ReloadPlans : PlanAction
    data object MorePlans : PlanAction
    data class Open(val id: String) : PlanAction
    data object Edit : PlanAction
    data object ReloadConflict : PlanAction
    data object CancelConflict : PlanAction
    data object ConfirmExit : PlanAction
    data object CancelExit : PlanAction
}

/** Screen state owned by RegisterViewModel's scope. No I/O starts during construction. */
class PlanController(private val gateway: PlanGateway, private val scope: CoroutineScope, private val onSessionEnded: () -> Unit) {
    private val mutable = MutableStateFlow(PlanUiState())
    val state = mutable.asStateFlow()
    private var referenceJob: Job? = null
    private var listJob: Job? = null
    private var formJob: Job? = null
    private var branchJob: Job? = null
    private var retryJob: Job? = null
    private var owner: String? = null

    fun clear() {
        referenceJob?.cancel(); listJob?.cancel(); formJob?.cancel(); branchJob?.cancel()
        retryJob?.cancel()
        referenceJob = null; listJob = null; formJob = null; branchJob = null
        owner = null; mutable.value = PlanUiState()
    }
    fun home(ownerId: String) {
        if (owner != ownerId) { clear(); owner = ownerId }
        if (state.value.step == PlanStep.CLOSED) loadPlans()
    }
    fun onAction(action: PlanAction) {
        when (action) {
            PlanAction.New -> if (!state.value.saving) {
                mutable.update { PlanUiState(step = PlanStep.BUDGET, plans = it.plans, nextCursor = it.nextCursor) }
                loadReferences()
            }
            PlanAction.Back -> back()
            PlanAction.Continue -> next()
            PlanAction.Save -> save()
            is PlanAction.Budget -> edit { it.copy(budget = action.value.take(128)) }
            is PlanAction.Retailer -> selectRetailer(action.id)
            is PlanAction.RetailerQuery -> if (!state.value.frozen) mutable.update { it.copy(retailerQuery = action.value.take(200)) }
            is PlanAction.Category -> if (action.id in state.value.draft.categoryIds || state.value.categories.any { it.id == action.id && it.status == "ACTIVE" }) edit {
                it.copy(categoryIds = if (action.id in it.categoryIds) it.categoryIds - action.id else it.categoryIds + action.id)
            }
            is PlanAction.Branches -> if (!state.value.frozen) {
                mutable.update { it.copy(branchesOpen = true, branchRetailer = action.retailerId, branchQuery = "", branches = emptyList(), branchCursor = null) }
                loadBranches()
            }
            is PlanAction.BranchQuery -> if (!state.value.frozen) {
                branchJob?.cancel()
                mutable.update { it.copy(branchQuery = action.value.take(200), branchCursor = null, branches = emptyList(),
                    branchesLoading = false, branchError = null, branchSearchPending = true) }
            }
            PlanAction.SearchBranches -> loadBranches()
            PlanAction.MoreBranches -> loadBranches(more = true)
            is PlanAction.Store -> selectStore(action.id)
            PlanAction.CloseBranches -> { branchJob?.cancel(); mutable.update { it.copy(branchesOpen = false, branchesLoading = false) } }
            PlanAction.RetryReferences -> loadReferences()
            PlanAction.ReloadPlans -> loadPlans()
            PlanAction.MorePlans -> loadPlans(more = true)
            is PlanAction.Open -> open(action.id)
            PlanAction.Edit -> editSaved()
            PlanAction.ReloadConflict -> state.value.saved?.plan?.id?.let { open(it) }
            PlanAction.CancelConflict -> mutable.update { it.copy(conflict = false) }
            PlanAction.ConfirmExit -> close()
            PlanAction.CancelExit -> mutable.update { it.copy(confirmExit = false) }
        }
    }
    private fun edit(change: (PlanDraft) -> PlanDraft) {
        if (state.value.frozen) return
        mutable.update { it.copy(draft = change(it.draft), errors = emptyMap(), message = null) }
    }
    private fun selectRetailer(id: String) {
        if (state.value.draft.selections.none { it.retailerId == id } && state.value.retailers.none { it.id == id && it.status == "ACTIVE" }) return
        choose { current -> if (current.any { it.retailerId == id }) current.filterNot { it.retailerId == id } else current + Selection(id, null) }
    }
    private fun selectStore(id: String) {
        val store = state.value.stores[id] ?: return
        choose { current -> if (current.any { it.storeId == id }) current.filterNot { it.storeId == id }
            else current.filterNot { it.storeId == null && it.retailerId != null && it.retailerId == store.retailerId } + Selection(store.retailerId, id) }
    }
    private fun choose(change: (List<Selection>) -> List<Selection>) {
        if (state.value.frozen) return
        val choices = change(state.value.draft.selections)
        val limit = state.value.maxStores
        if (limit == null || choices.size > limit) {
            mutable.update { it.copy(errors = it.errors + ("selections" to if (limit == null) "Chưa tải được giới hạn cửa hàng. Hãy thử lại." else "Chỉ chọn tối đa $limit nơi mua. Bỏ bớt một lựa chọn trước.")) }
        } else edit { it.copy(selections = choices) }
    }
    private fun back() {
        val s = state.value
        if (s.saving || s.opening) return
        if (s.branchesOpen) { onAction(PlanAction.CloseBranches); return }
        if (s.pending != null || s.step == PlanStep.BUDGET) {
            val unchanged = if (s.editing) s.saved?.plan?.let { s.draft == PlanDraft.from(it) } == true else s.draft == PlanDraft()
            if (s.pending == null && unchanged) close() else mutable.update { it.copy(confirmExit = true) }
            return
        }
        when (s.step) {
            PlanStep.STORES -> mutable.update { it.copy(step = PlanStep.BUDGET, errors = emptyMap(), message = null) }
            PlanStep.CATEGORIES -> mutable.update { it.copy(step = PlanStep.STORES, errors = emptyMap(), message = null) }
            PlanStep.DETAIL -> close()
            PlanStep.BUDGET, PlanStep.CLOSED -> Unit
        }
    }
    private fun close() {
        if (state.value.saving) return
        referenceJob?.cancel(); branchJob?.cancel(); formJob?.cancel(); retryJob?.cancel()
        mutable.update { PlanUiState(plans = it.plans, nextCursor = it.nextCursor) }
        loadPlans()
    }
    private fun next() {
        val s = state.value
        if (s.frozen) return
        val errors = s.draft.errors(s.maxStores)
        val field = when (s.step) { PlanStep.BUDGET -> "budget"; PlanStep.STORES -> "selections"; PlanStep.CATEGORIES, PlanStep.CLOSED, PlanStep.DETAIL -> null }
        if (field != null && errors[field] != null) { mutable.update { it.copy(errors = mapOf(field to errors.getValue(field))) }; return }
        when (s.step) {
            PlanStep.BUDGET -> mutable.update { it.copy(step = PlanStep.STORES, errors = emptyMap()) }
            PlanStep.STORES -> mutable.update { it.copy(step = PlanStep.CATEGORIES, errors = emptyMap()) }
            PlanStep.CATEGORIES -> save()
            PlanStep.CLOSED, PlanStep.DETAIL -> Unit
        }
    }
    private fun loadReferences() {
        if (referenceJob?.isActive == true) return
        mutable.update { it.copy(referencesLoading = true, referenceError = null) }
        referenceJob = scope.launch {
            try {
                val limit = gateway.planMaxStores().also { require(it in 1..3) }
                suspend fun all(kind: String): List<PlanReference> {
                    val result = mutableListOf<PlanReference>(); val seen = mutableSetOf<String>()
                    var cursor: String? = null
                    do {
                        val page = gateway.planReferences(kind, cursor)
                        result += page.items
                        cursor = page.nextCursor
                        if (cursor != null && !seen.add(cursor)) throw AuthFailure.InvalidResponse()
                    } while (cursor != null)
                    return result.distinctBy { it.id }
                }
                val retailers = all("retailers"); val categories = all("categories")
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(referencesLoading = false, retailers = retailers, categories = categories,
                    maxStores = if (it.editing || it.step == PlanStep.DETAIL) it.saved?.plan?.maxStores ?: limit else limit) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (!ended(failure)) mutable.update { it.copy(referencesLoading = false, referenceError = "Chưa tải được nơi mua hoặc nhóm hàng. Kiểm tra kết nối và thử lại.") } }
        }
    }
    private fun loadPlans(more: Boolean = false) {
        if (listJob?.isActive == true) return
        val cursor = if (more) state.value.nextCursor ?: return else null
        mutable.update { it.copy(plansLoading = true, plansError = null) }
        listJob = scope.launch {
            try {
                val page = gateway.plans(cursor)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(plansLoading = false, plans = ((if (more) it.plans else emptyList()) + page.items).distinctBy { p -> p.id }, nextCursor = page.nextCursor) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (!ended(failure)) mutable.update { it.copy(plansLoading = false, plansError = "Chưa tải được kế hoạch đã lưu.") } }
        }
    }
    private fun loadBranches(more: Boolean = false) {
        if (!state.value.branchesOpen || state.value.frozen) return
        if (more && branchJob?.isActive == true) return
        val s = state.value
        val cursor = if (more) s.branchCursor ?: return else null
        branchJob?.cancel()
        mutable.update { it.copy(branchesLoading = true, branchError = null, branchSearchPending = false) }
        branchJob = scope.launch {
            try {
                val page = gateway.planStores(s.branchRetailer, s.branchQuery.trim(), cursor)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(branchesLoading = false,
                    branches = ((if (more) it.branches else emptyList()) + page.items).distinctBy { b -> b.id }, branchCursor = page.nextCursor,
                    stores = it.stores + page.items.associateBy { b -> b.id }, branchWarnings = page.warnings, attribution = page.attribution) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (!ended(failure)) mutable.update { it.copy(branchesLoading = false, branchError = "Chưa tải được chi nhánh. Bạn vẫn có thể chọn cả chuỗi.") } }
        }
    }
    private fun open(id: String) {
        if (state.value.saving || state.value.opening) return
        referenceJob?.cancel(); formJob?.cancel(); branchJob?.cancel()
        mutable.update { it.copy(step = PlanStep.DETAIL, opening = true, message = null, saved = null, conflict = false, branchesOpen = false, pending = null, editing = false) }
        formJob = scope.launch {
            try {
                val view = gateway.plan(id)
                val stores = view.plan.selections.mapNotNull { it.storeId }.associateWith { gateway.planStore(it) }
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(opening = false, saved = view, draft = PlanDraft.from(view.plan), maxStores = view.plan.maxStores, stores = it.stores + stores) }
                loadReferences()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (!ended(failure)) mutable.update { it.copy(opening = false, message = "Chưa mở được kế hoạch. Hãy quay lại danh sách và thử lại.") } }
        }
    }
    private fun editSaved() {
        val s = state.value; val plan = s.saved?.plan ?: return
        if (s.frozen || plan.status !in editablePlans) return
        mutable.update { it.copy(step = PlanStep.BUDGET, editing = true, draft = PlanDraft.from(plan), maxStores = plan.maxStores, message = null, errors = emptyMap()) }
    }
    private fun save() {
        val s = state.value
        if (s.saving || s.opening || s.retrySeconds > 0 || s.step != PlanStep.CATEGORIES) return
        val errors = s.draft.errors(s.maxStores)
        if (errors.isNotEmpty()) { mutable.update { it.copy(errors = errors) }; return }
        val mutation = s.pending ?: PlanMutation(UUID.randomUUID().toString(), s.draft.body(if (s.editing) s.saved?.plan?.version else null),
            if (s.editing) s.saved?.plan?.id else null)
        mutable.update { it.copy(saving = true, pending = mutation, errors = emptyMap(), message = null) }
        formJob = scope.launch {
            try {
                val saved = gateway.savePlan(mutation)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(saving = false, pending = null, saved = saved, draft = PlanDraft.from(saved.plan),
                    step = PlanStep.DETAIL, editing = false, message = "Đã lưu kế hoạch.") }
                loadPlans()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (ended(failure)) return@launch
                if (failure is AuthFailure.Http && failure.status == 429) {
                    val seconds = failure.retryAfter?.coerceIn(1, 3600) ?: 1
                    mutable.update { it.copy(saving = false, retrySeconds = seconds, message = "Thao tác quá nhanh. Hãy chờ rồi thử lưu lại.") }
                    retryJob?.cancel()
                    retryJob = scope.launch { repeat(seconds) { delay(1000); mutable.update { it.copy(retrySeconds = (it.retrySeconds - 1).coerceAtLeast(0)) } } }
                } else if (failure is AuthFailure.Http && failure.status in 400..499) {
                    val conflict = failure.code == "VERSION_CONFLICT"
                    val fields = failure.fields.mapValues { (field, _) -> when (field) {
                        "budget" -> "Kiểm tra ngân sách và đơn vị tiền."
                        "selections" -> "Nơi mua không còn hợp lệ. Hãy chọn lại."
                        "category_ids" -> "Nhóm hàng không còn hợp lệ. Hãy chọn lại."
                        else -> "Kiểm tra thông tin đã nhập."
                    } }
                    mutable.update { it.copy(saving = false, pending = null, errors = fields, conflict = conflict,
                        step = when { "budget" in fields -> PlanStep.BUDGET; "selections" in fields -> PlanStep.STORES; else -> it.step },
                        message = if (conflict) "Kế hoạch đã thay đổi trên thiết bị khác." else "Chưa lưu được kế hoạch. Kiểm tra lại thông tin hoặc tải lại dữ liệu.") }
                } else mutable.update { it.copy(saving = false, message = "Chưa xác nhận được kết quả lưu. Nhấn Thử lưu lại để gửi đúng yêu cầu cũ, tránh tạo nháp trùng.") }
            }
        }
    }
    private fun ended(failure: Exception): Boolean {
        if (failure !is AuthFailure.SessionEnded) return false
        clear(); onSessionEnded(); return true
    }
}

val editablePlans = setOf(PlanState.DRAFT, PlanState.BROWSING_PRODUCTS, PlanState.BUILDING_LIST, PlanState.COMPARING, PlanState.OPTIMIZING)
fun PlanUiState.placeName(selection: Selection): String = selection.storeId?.let { stores[it]?.name ?: "Chi nhánh đã lưu" }
    ?: retailers.firstOrNull { it.id == selection.retailerId }?.name ?: "Chuỗi đã lưu"
