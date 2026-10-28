package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

data class CatalogUiState(
    val plan: ShoppingPlanView? = null, val filter: CatalogFilter = CatalogFilter(), val search: String = "",
    val items: List<Offer> = emptyList(), val nextCursor: String? = null, val status: String = "LOADING",
    val retailers: List<RetailerCatalogResult> = emptyList(), val warnings: List<ShoppingWarning> = emptyList(),
    val loading: Boolean = false, val refreshing: Boolean = false, val error: String? = null, val message: String? = null,
    val pending: CatalogRefreshIntent? = null, val retrySeconds: Int = 0,
    val selectedProduct: String? = null, val detail: CatalogDetail? = null, val detailLoading: Boolean = false, val detailError: String? = null,
    val filtersOpen: Boolean = false, val brands: List<PlanReference> = emptyList(), val brandCursor: String? = null, val brandsLoading: Boolean = false,
)
sealed interface CatalogAction {
    data class Query(val value: String): CatalogAction
    data object Search: CatalogAction
    data class Retailer(val id: String?): CatalogAction
    data class Category(val id: String?): CatalogAction
    data class Brand(val id: String?): CatalogAction
    data class Sort(val value: String): CatalogAction
    data object Reload: CatalogAction
    data object More: CatalogAction
    data object Refresh: CatalogAction
    data class Details(val id: String): CatalogAction
    data object CloseDetails: CatalogAction
    data class Filters(val open: Boolean): CatalogAction
    data object MoreBrands: CatalogAction
}

/** Owned by the same ViewModel scope/session as Plan; composition only renders and sends actions. */
class CatalogController(private val gateway: PlanGateway, private val scope: CoroutineScope,
    private val onPlan: (ShoppingPlanView)->Unit, private val onSessionEnded: ()->Unit) {
    private val mutable=MutableStateFlow(CatalogUiState())
    val state=mutable.asStateFlow()
    private var loadJob: Job?=null;private var detailJob: Job?=null;private var timerJob: Job?=null;private var brandJob: Job?=null
    private val seenCursors=mutableSetOf<String>()
    fun clear() { close();mutable.value=CatalogUiState() }
    fun close() { loadJob?.cancel();detailJob?.cancel();timerJob?.cancel();brandJob?.cancel();seenCursors.clear() }
    fun open(view: ShoppingPlanView) {
        close()
        val filter=state.value.takeIf { it.plan?.plan?.id==view.plan.id }?.filter ?: CatalogFilter()
        mutable.value=CatalogUiState(plan=view,filter=filter,search=filter.query)
        load(bootstrap=true)
    }
    fun onAction(action: CatalogAction) {
        when(action) {
            is CatalogAction.Query -> mutable.update { it.copy(search=action.value.take(200)) }
            CatalogAction.Search -> change(state.value.filter.copy(query=state.value.search.trim()))
            is CatalogAction.Retailer -> change(state.value.filter.copy(retailer=action.id))
            is CatalogAction.Category -> change(state.value.filter.copy(category=action.id))
            is CatalogAction.Brand -> change(state.value.filter.copy(brand=action.id))
            is CatalogAction.Sort -> if(action.value in setOf("RELEVANCE","PRICE_ASC","PRICE_DESC")) change(state.value.filter.copy(sort=action.value))
            CatalogAction.Reload -> load()
            CatalogAction.More -> load(more=true)
            CatalogAction.Refresh -> refresh()
            is CatalogAction.Details -> details(action.id)
            CatalogAction.CloseDetails -> { detailJob?.cancel();mutable.update { it.copy(selectedProduct=null,detail=null,detailLoading=false,detailError=null) } }
            is CatalogAction.Filters -> { mutable.update { it.copy(filtersOpen=action.open) };if(action.open && state.value.brands.isEmpty()) brands(false) }
            CatalogAction.MoreBrands -> brands(true)
        }
    }
    private fun change(filter: CatalogFilter) {
        if(state.value.refreshing) return
        mutable.update { it.copy(filter=filter) };load()
    }
    private fun load(more: Boolean=false,bootstrap: Boolean=false) {
        if(state.value.refreshing || (more && loadJob?.isActive==true)) return
        val s=state.value;val id=s.plan?.plan?.id ?: return
        val cursor=if(more) s.nextCursor ?: return else null
        loadJob?.cancel()
        if(!more) seenCursors.clear()
        mutable.update { it.copy(loading=true,error=null,message=null,items=if(more) it.items else emptyList(),nextCursor=if(more) it.nextCursor else null) }
        loadJob=scope.launch {
            try {
                val page=gateway.catalog(id,s.filter,cursor)
                currentCoroutineContext().ensureActive()
                apply(page,more)
                val view=gateway.plan(id)
                currentCoroutineContext().ensureActive();updatePlan(view)
                if(page.status=="LOADING") poll(id,s.filter)
                else if(bootstrap && view.plan.status==PlanState.DRAFT && view.plan.catalogStatus!="FAILED") {
                    refreshFromJob(id)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { failure(failure,more) }
            finally { currentCoroutineContext().ensureActive();mutable.update { it.copy(loading=false,refreshing=false) } }
        }
    }
    private fun apply(page: CatalogPage,more: Boolean) {
        if(page.nextCursor!=null && !seenCursors.add(page.nextCursor)) throw AuthFailure.InvalidResponse()
        mutable.update { it.copy(items=((if(more) it.items else emptyList())+page.items).distinctBy { offer -> offer.ref },nextCursor=page.nextCursor,
            status=page.status,retailers=page.retailers,warnings=page.warnings,loading=page.status=="LOADING") }
        val seconds=page.retailers.mapNotNull { it.retrySeconds }.maxOrNull() ?: 0
        if(seconds>0) countdown(seconds)
    }
    private fun refresh() {
        if(state.value.refreshing || state.value.retrySeconds>0) return
        val id=state.value.plan?.plan?.id ?: return
        loadJob?.cancel();seenCursors.clear()
        loadJob=scope.launch {
            try { refreshFromJob(id) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { failure(failure,false) }
            finally { currentCoroutineContext().ensureActive();mutable.update { it.copy(refreshing=false,loading=false) } }
        }
    }
    private suspend fun refreshFromJob(id: String) {
        val view=state.value.plan ?: return
        val intent=state.value.pending ?: CatalogRefreshIntent(UUID.randomUUID().toString(),JSONObject().put("expected_version",view.plan.version).toString())
        mutable.update { it.copy(pending=intent,refreshing=true,error=null,message=null) }
        val plan=gateway.refreshCatalog(id,intent)
        currentCoroutineContext().ensureActive()
        updatePlan(view.copy(plan=plan))
        mutable.update { it.copy(pending=null,status="LOADING",nextCursor=null) }
        poll(id,state.value.filter)
    }
    private suspend fun poll(id: String,filter: CatalogFilter) {
        repeat(80) {
            delay(1500)
            val page=gateway.catalog(id,filter,null)
            currentCoroutineContext().ensureActive();seenCursors.clear();apply(page,false)
            if(page.status!="LOADING") {
                val view=gateway.plan(id);currentCoroutineContext().ensureActive();updatePlan(view)
                mutable.update { it.copy(message="Đã tải dữ liệu sản phẩm.") };return
            }
        }
        mutable.update { it.copy(message="Dữ liệu vẫn đang được cập nhật. Bạn có thể tải lại để kiểm tra tiến độ.") }
    }
    private fun updatePlan(view: ShoppingPlanView) { mutable.update { it.copy(plan=view) };onPlan(view) }
    private fun failure(failure: Exception,more: Boolean) {
        if(failure is AuthFailure.SessionEnded) { clear();onSessionEnded();return }
        if(failure is AuthFailure.Http && failure.code in setOf("VERSION_CONFLICT","CATALOG_REFRESH_IN_PROGRESS")) {
            mutable.update { it.copy(pending=null,error="Kế hoạch đã thay đổi. Tải lại dữ liệu trước khi cập nhật.") };return
        }
        if(failure is AuthFailure.Http && failure.code=="INVALID_CURSOR") {
            mutable.update { it.copy(nextCursor=null,error="Dữ liệu đã thay đổi. Tải lại danh sách từ đầu.") };return
        }
        if(failure is AuthFailure.Http && failure.status==429) countdown(failure.retryAfter?.coerceIn(1,3600) ?: 1)
        mutable.update { it.copy(error=if(more) "Chưa tải được trang tiếp theo. Các sản phẩm đã tải được giữ lại." else "Chưa tải được danh sách sản phẩm. Kiểm tra kết nối và thử lại.") }
    }
    private fun details(id: String) {
        val planId=state.value.plan?.plan?.id ?: return
        detailJob?.cancel();mutable.update { it.copy(selectedProduct=id,detail=null,detailLoading=true,detailError=null) }
        detailJob=scope.launch {
            try {
                val detail=gateway.productDetail(planId,id);currentCoroutineContext().ensureActive()
                mutable.update { it.copy(detail=detail,detailLoading=false) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { if(failure is AuthFailure.SessionEnded) { clear();onSessionEnded() } else mutable.update { it.copy(detailLoading=false,detailError="Chưa mở được chi tiết. Hãy thử lại.") } }
        }
    }
    private fun countdown(seconds: Int) {
        timerJob?.cancel();mutable.update { it.copy(retrySeconds=seconds.coerceIn(0,86400)) }
        timerJob=scope.launch { repeat(state.value.retrySeconds) { delay(1000);mutable.update { it.copy(retrySeconds=(it.retrySeconds-1).coerceAtLeast(0)) } } }
    }
    private fun brands(more: Boolean) {
        if(brandJob?.isActive==true) return
        val cursor=if(more) state.value.brandCursor ?: return else null
        mutable.update { it.copy(brandsLoading=true) }
        brandJob=scope.launch {
            try {
                val page=gateway.planReferences("brands",cursor);currentCoroutineContext().ensureActive()
                mutable.update { it.copy(brands=((if(more) it.brands else emptyList())+page.items).distinctBy { b -> b.id },brandCursor=page.nextCursor,brandsLoading=false) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { if(failure is AuthFailure.SessionEnded) { clear();onSessionEnded() } else mutable.update { it.copy(brandsLoading=false) } }
        }
    }
}
