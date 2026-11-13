package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class ListControllerTest {
    private fun listView(priority: String = "RECOMMENDED", quantity: String = "0.2", status: PlanState = PlanState.BUILDING_LIST, version: Int = 3): ShoppingPlanView {
        val base = samplePlan(version = version); val offer = catalogOffer()
        // Fixture offer: quantity_step 0.1.
        val item = ShoppingItem("i1", offer.product!!.id, null, offer.ref, Quantity.parse(quantity), "kg", priority, offer, Money("VND", BigDecimal("6000")))
        return base.copy(plan = base.plan.copy(status = status), items = listOf(item))
    }
    private class Gateway(var view: ShoppingPlanView) : PlanGateway {
        val changes = mutableListOf<Pair<String, JSONObject>>(); val removes = mutableListOf<Boolean>(); val finalizes = mutableListOf<JSONObject>()
        var failure: Exception? = null; var finalizeFailure: Exception? = null; var reviews = 0; var changedIds = listOf<String>()
        override suspend fun planMaxStores() = 3
        override suspend fun planReferences(kind: String, cursor: String?) = PlanReferencePage(emptyList(), null)
        override suspend fun planStores(retailerId: String?, query: String, cursor: String?) = PlanStorePage(emptyList(), null, emptyList(), emptyList())
        override suspend fun planStore(id: String) = PlanStore(id, "r1", "Store", null, false)
        override suspend fun plans(cursor: String?) = PlanPage(listOf(view.plan), null)
        override suspend fun plan(id: String) = view
        override suspend fun savePlan(mutation: PlanMutation) = view
        private fun bump(status: PlanState = view.plan.status) = view.copy(plan = view.plan.copy(version = view.plan.version + 1, status = status)).also { view = it }
        override suspend fun changeItem(planId: String, itemId: String, mutation: PlanMutation): ShoppingPlanView {
            changes += itemId to JSONObject(mutation.body); failure?.let { throw it }; return bump()
        }
        override suspend fun removeItem(planId: String, itemId: String, version: Int, confirmEssential: Boolean): ShoppingPlanView {
            removes += confirmEssential; return bump().copy(items = emptyList()).also { view = it }
        }
        override suspend fun reviewPlan(planId: String, version: Int): FinalizationReview { reviews++; return FinalizationReview("review-$reviews", version, view, changedIds, 1, true) }
        override suspend fun finalizePlan(planId: String, mutation: PlanMutation) {
            finalizes += JSONObject(mutation.body); finalizeFailure?.let { finalizeFailure = null; throw it }; bump(PlanState.FINALIZED)
        }
        override suspend fun reopenPlan(planId: String, mutation: PlanMutation) = bump(PlanState.BUILDING_LIST)
    }
    private fun TestScope.controller(g: Gateway, seen: MutableList<ShoppingPlanView> = mutableListOf()) =
        ListController(g, backgroundScope + UnconfinedTestDispatcher(testScheduler), { seen += it }, {}).also { it.open(g.view) }

    @Test fun quantityUsesTheOfferStepAndNeverSendsZero() = runTest {
        val g = Gateway(listView(quantity = "0.1")); val c = controller(g)
        c.onAction(ListAction.Quantity("i1", false)); assertTrue("0.1 - 0.1 would be 0; removing is explicit", g.changes.isEmpty())
        c.onAction(ListAction.Quantity("i1", true))
        val body = g.changes.single().second
        assertEquals("0.2", body.getString("quantity")); assertEquals(3, body.getInt("expected_version"))
        c.onAction(ListAction.Priority("i1", "ESSENTIAL")); assertEquals("ESSENTIAL", g.changes.last().second.getString("priority"))
        assertEquals(5, c.state.value.view!!.plan.version)
        c.onAction(ListAction.Priority("i1", "URGENT")); assertEquals(2, g.changes.size)
    }
    @Test fun essentialRemovalNeedsConfirmation() = runTest {
        val g = Gateway(listView(priority = "ESSENTIAL")); val c = controller(g)
        c.onAction(ListAction.Remove("i1")); assertTrue(g.removes.isEmpty()); assertEquals("i1", c.state.value.confirmRemove?.id)
        c.onAction(ListAction.CancelRemove); assertNull(c.state.value.confirmRemove)
        c.onAction(ListAction.Remove("i1")); c.onAction(ListAction.ConfirmRemove)
        assertEquals(listOf(true), g.removes); assertTrue(c.state.value.view!!.items.isEmpty())
    }
    @Test fun finalizeNeedsPriceConsentAndRetriesWithAFreshReview() = runTest {
        val g = Gateway(listView()); g.changedIds = listOf("i1"); val seen = mutableListOf<ShoppingPlanView>(); val c = controller(g, seen)
        c.onAction(ListAction.Review); assertEquals(ListScreen.REVIEW, c.state.value.screen); assertEquals("review-1", c.state.value.review?.id)
        c.onAction(ListAction.Finalize); assertTrue("changed prices need explicit consent", g.finalizes.isEmpty())
        c.onAction(ListAction.ConfirmPrices(true))
        g.finalizeFailure = AuthFailure.Http(409, "PRICE_CHANGED", emptyMap(), null)
        c.onAction(ListAction.Finalize)
        assertEquals("review-2", c.state.value.review?.id); assertFalse("old consent is not reused", c.state.value.confirmPriceChanges)
        c.onAction(ListAction.ConfirmPrices(true)); c.onAction(ListAction.Finalize)
        val body = g.finalizes.last()
        assertEquals("review-2", body.getString("review_id")); assertTrue(body.getBoolean("confirm_price_changes"))
        assertEquals(ListScreen.FINALIZED, c.state.value.screen); assertEquals(PlanState.FINALIZED, seen.last().plan.status)
        c.onAction(ListAction.Reopen); assertEquals(ListScreen.LIST, c.state.value.screen)
    }
    @Test fun versionConflictReloadsAndBackLeavesReviewFirst() = runTest {
        val g = Gateway(listView()); val c = controller(g)
        c.onAction(ListAction.Review); assertTrue(c.back()); assertEquals(ListScreen.LIST, c.state.value.screen); assertFalse(c.back())
        g.failure = AuthFailure.Http(409, "VERSION_CONFLICT", emptyMap(), null); g.view = g.view.copy(plan = g.view.plan.copy(version = 9))
        c.onAction(ListAction.Quantity("i1", true))
        assertNotNull(c.state.value.error); assertEquals(9, c.state.value.view!!.plan.version); assertFalse(c.state.value.busy)
    }
    @Test fun finalizedPlansOpenReadOnly() = runTest {
        val c = controller(Gateway(listView(status = PlanState.FINALIZED)))
        assertEquals(ListScreen.FINALIZED, c.state.value.screen)
    }
}
