package com.toked.smartshopping.shopping

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

class ListUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun money(v: String) = Money("VND", BigDecimal(v))
    private fun view(status: PlanState = PlanState.BUILDING_LIST, priority: String = "ESSENTIAL", price: String = "32800"): ShoppingPlanView {
        val product = Product("p1", "Chân giò heo nhập khẩu", "c1", "kg", Quantity.parse("1"), null, null, null, null)
        val offer = Offer(OfferRef("CATALOG", "o1"), "v1", product, null, "r1", null, "VND", money(price), "https://fixture.example.test",
            "2026-11-13T00:00:00Z", "RETAILER", "FRESH", "UNKNOWN", null, null, null, null, emptyList())
        val subtotal = money(BigDecimal(price).multiply(BigDecimal(2)).toPlainString())
        val item = ShoppingItem("i1", "p1", null, offer.ref, Quantity.parse("2"), "kg", priority, offer, subtotal)
        val budget = money("200000")
        val summary = BudgetSummary(budget, subtotal, money(BigDecimal("200000").subtract(subtotal.amount).toPlainString()), true, emptyList(), money("0"), emptyList())
        return ShoppingPlanView(ShoppingPlan("plan", 4, if (status == PlanState.FINALIZED) 1 else 0, status, budget, listOf(Selection("r1", null)), listOf("c1"), 3,
            summary, "READY", "2026-11-13T00:00:00Z", "2026-11-13T00:00:00Z"), listOf(item), if (status == PlanState.FINALIZED) 1 else null, null, emptyList())
    }
    private val refs = PlanUiState(retailers = listOf(PlanReference("r1", "Bách Hóa Xanh", "ACTIVE")))
    private fun show(state: ListUiState, list: MutableList<ListAction> = mutableListOf(), plan: MutableList<PlanAction> = mutableListOf()) =
        compose.setContent { SmartShoppingTheme(darkTheme = false) { ListContent(state, refs, { list += it }, { plan += it }) } }

    @Test fun selectedListEditsQuantityPriorityAndAsksBeforeRemovingEssential() {
        val actions = mutableListOf<ListAction>(); val plan = mutableListOf<PlanAction>()
        show(ListUiState(view = view()), actions, plan)
        compose.onAllNodesWithText("Bách Hóa Xanh").onFirst().assertIsDisplayed()
        compose.onNodeWithText("32.800 ₫ / kg = 65.600 ₫").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tăng số lượng Chân giò heo nhập khẩu").performClick()
        assertEquals(ListAction.Quantity("i1", true), actions.last())
        compose.onNodeWithContentDescription("Mức ưu tiên của Chân giò heo nhập khẩu: Thiết yếu").performClick()
        compose.onNodeWithText("Tùy chọn").performClick()
        assertEquals(ListAction.Priority("i1", "OPTIONAL"), actions.last())
        compose.onNodeWithContentDescription("Bỏ Chân giò heo nhập khẩu khỏi danh sách").performClick()
        assertEquals(ListAction.Remove("i1"), actions.last())
        compose.onNodeWithText("Chốt kế hoạch").performClick(); assertEquals(ListAction.Review, actions.last())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("+ Thêm sản phẩm vào kế hoạch"))
        compose.onNodeWithText("+ Thêm sản phẩm vào kế hoạch").performClick(); assertEquals(PlanAction.AddProducts, plan.last())
    }
    @Test fun essentialDialogConfirmsExplicitly() {
        val actions = mutableListOf<ListAction>()
        val v = view(); show(ListUiState(view = v, confirmRemove = v.items.single()), actions)
        compose.onNodeWithText("Món hàng thiết yếu").assertIsDisplayed()
        compose.onNodeWithText("Giữ lại").performClick(); assertEquals(ListAction.CancelRemove, actions.last())
        compose.onNodeWithText("Xác nhận bỏ").performClick(); assertEquals(ListAction.ConfirmRemove, actions.last())
    }
    @Test fun reviewNeedsPriceConsentBeforeFinalizing() {
        val actions = mutableListOf<ListAction>()
        val before = view(); val after = view(price = "35000")
        val review = FinalizationReview("r1", 4, after, listOf("i1"), 1, true)
        show(ListUiState(screen = ListScreen.REVIEW, view = before, review = review), actions)
        compose.onNodeWithText("Sẵn sàng để chốt").assertIsDisplayed()
        compose.onNodeWithText("Xác nhận chốt").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Giá cũ 32.800 ₫"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tôi đồng ý dùng giá mới cho 1 món đã đổi giá."))
        compose.onNodeWithText("Tôi đồng ý dùng giá mới cho 1 món đã đổi giá.").performClick()
        assertEquals(ListAction.ConfirmPrices(true), actions.last())
    }
    @Test fun failedReviewOffersRetryInsteadOfEndlessLoading() {
        val actions = mutableListOf<ListAction>()
        show(ListUiState(screen = ListScreen.REVIEW, view = view(), error = "Chưa thực hiện được (404 RESOURCE_NOT_FOUND)."), actions)
        compose.onNodeWithText("Đang kiểm tra giá và tổng tiền…").assertDoesNotExist()
        compose.onNodeWithText("Thử kiểm tra lại").performClick(); assertEquals(ListAction.Review, actions.last())
    }
    @Test fun finalizedShowsSnapshotAndNextSteps() {
        val actions = mutableListOf<ListAction>(); val plan = mutableListOf<PlanAction>()
        show(ListUiState(screen = ListScreen.FINALIZED, view = view(PlanState.FINALIZED)), actions, plan)
        compose.onNodeWithText("Kế hoạch đã chốt!").assertIsDisplayed()
        compose.onNodeWithText("Sửa kế hoạch").performClick(); assertEquals(ListAction.Reopen, actions.last())
        compose.onNodeWithText("Về trang chủ").performClick(); assertEquals(PlanAction.Home, plan.last())
    }
}
