package com.toked.smartshopping.shopping

import com.toked.smartshopping.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

internal fun samplePlan(id: String = "plan-one", version: Int = 1, budget: String = "500000", max: Int = 3): ShoppingPlanView {
    val amount = Money("VND", BigDecimal(budget)); val zero = Money("VND", BigDecimal.ZERO)
    val summary = BudgetSummary(amount, zero, amount, true, emptyList(), zero, emptyList())
    return ShoppingPlanView(ShoppingPlan(id, version, 0, PlanState.DRAFT, amount, listOf(Selection("r1", null)), listOf("c1"), max,
        summary, "EMPTY", "2026-10-27T00:00:00Z", "2026-10-27T00:00:00Z"), emptyList(), null, null, emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlanControllerTest {
    private class Gateway : PlanGateway {
        var max = 3
        var refsFailure: Exception? = null
        var saveFailure: Exception? = null
        var readFailure: Exception? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var referenceGate: CompletableDeferred<Unit>? = null
        var saved = samplePlan()
        var pages = listOf(saved.plan)
        val requests = mutableListOf<PlanMutation>()
        val queries = mutableListOf<Triple<String?, String, String?>>()
        var storeCursor: String? = "more-stores"
        override suspend fun planMaxStores(): Int { refsFailure?.let { throw it }; referenceGate?.await(); return max }
        override suspend fun planReferences(kind: String, cursor: String?) = PlanReferencePage(
            if (kind == "retailers") (1..4).map { PlanReference("r$it", "Chain $it", "ACTIVE") }
            else listOf(PlanReference("c1", "Food", "ACTIVE"), PlanReference("c2", "Home", "ACTIVE"), PlanReference("old", "Old", "INACTIVE")), null)
        override suspend fun planStores(retailerId: String?, query: String, cursor: String?): PlanStorePage {
            queries += Triple(retailerId, query, cursor)
            return PlanStorePage(listOf(PlanStore("s1", "r1", "Branch 1", "Test area", false)), storeCursor, emptyList(), emptyList())
        }
        override suspend fun planStore(id: String) = PlanStore(id, "r1", "Branch 1", null, false)
        override suspend fun plans(cursor: String?) = PlanPage(pages, null)
        override suspend fun plan(id: String): ShoppingPlanView { readFailure?.let { throw it }; return saved }
        override suspend fun savePlan(mutation: PlanMutation): ShoppingPlanView {
            requests += mutation; saveGate?.await(); saveFailure?.let { throw it }
            val body = JSONObject(mutation.body)
            saved = samplePlan(version = if (mutation.planId == null) 1 else saved.plan.version + 1, budget = body.getString("budget"))
            pages = listOf(saved.plan)
            return saved
        }
    }
    private fun TestScope.controller(gateway: Gateway = Gateway(), ended: () -> Unit = {}) =
        PlanController(gateway, backgroundScope + UnconfinedTestDispatcher(testScheduler), ended)
    private fun ready(c: PlanController) {
        c.onAction(PlanAction.New); c.onAction(PlanAction.Budget("500000")); c.onAction(PlanAction.Continue)
        c.onAction(PlanAction.Retailer("r1")); c.onAction(PlanAction.Continue); c.onAction(PlanAction.Category("c1"))
    }
    @Test fun budgetAndPayloadUseOnlyThreeInputsAndExactDecimalStrings() {
        listOf("", "-1", "0", "1e6", "500.000", "1.5", "12,000", "NaN", "9".repeat(129)).forEach { assertNotNull(PlanDraft(budget = it).budgetError()) }
        val draft = PlanDraft("999999999999999999999999999999", selections = listOf(Selection("r1", null)), categoryIds = listOf("c2", "c1"))
        assertTrue(draft.errors(3).isEmpty())
        val body = JSONObject(draft.body())
        assertEquals(setOf("budget", "currency", "selections", "category_ids"), body.keys().asSequence().toSet())
        assertEquals(draft.budget, body.getString("budget")); assertEquals("c1", body.getJSONArray("category_ids").getString(0))
        assertFalse(JSONObject(draft.body(4)).has("currency")); assertEquals(4, JSONObject(draft.body(4)).getInt("expected_version"))
    }
    @Test fun backPreservesAllInputsAndExitRequiresExplicitChoice() = runTest {
        val c = controller(); ready(c)
        c.onAction(PlanAction.Back); c.onAction(PlanAction.Back)
        assertEquals(PlanStep.BUDGET, c.state.value.step); assertEquals("500000", c.state.value.draft.budget)
        assertEquals(listOf("c1"), c.state.value.draft.categoryIds)
        assertEquals(listOf(Selection("r1", null)), c.state.value.draft.selections)
        c.onAction(PlanAction.Back); assertTrue(c.state.value.confirmExit)
        c.onAction(PlanAction.CancelExit); assertEquals("500000", c.state.value.draft.budget)
        c.onAction(PlanAction.Back); c.onAction(PlanAction.ConfirmExit); assertEquals(PlanStep.CLOSED, c.state.value.step)
    }
    @Test fun emptyFormLeavesWithoutDiscardPrompt() = runTest {
        val c = controller(); c.onAction(PlanAction.New); c.onAction(PlanAction.Back)
        assertEquals(PlanStep.CLOSED, c.state.value.step)
    }
    @Test fun validatesEveryStepWithoutPostingAndDoesNotAutoSelectPreferences() = runTest {
        val g = Gateway(); val c = controller(g); c.onAction(PlanAction.New)
        assertTrue(c.state.value.draft.selections.isEmpty()); assertTrue(c.state.value.draft.categoryIds.isEmpty())
        c.onAction(PlanAction.Continue); assertTrue("budget" in c.state.value.errors)
        c.onAction(PlanAction.Budget("500000")); c.onAction(PlanAction.Continue); c.onAction(PlanAction.Continue)
        assertTrue("selections" in c.state.value.errors)
        c.onAction(PlanAction.Retailer("r1")); c.onAction(PlanAction.Continue); c.onAction(PlanAction.Save)
        assertTrue("category_ids" in c.state.value.errors); assertTrue(g.requests.isEmpty())
    }
    @Test fun honorsProfileLimitAndBranchReplacesChainInsteadOfDuplicatingIt() = runTest {
        val c = controller(Gateway().apply { max = 1 }); c.onAction(PlanAction.New)
        c.onAction(PlanAction.Retailer("r1")); c.onAction(PlanAction.Retailer("r2"))
        assertEquals(1, c.state.value.draft.selections.size); assertTrue("selections" in c.state.value.errors)
        c.onAction(PlanAction.Branches("r1")); c.onAction(PlanAction.Store("s1"))
        assertEquals(listOf(Selection("r1", "s1")), c.state.value.draft.selections)
        c.onAction(PlanAction.Store("s1")); assertTrue(c.state.value.draft.selections.isEmpty())
    }
    @Test fun thirdPlaceIsAllowedAndFourthIsExplainedWithoutChangingSelections() = runTest {
        val c = controller(); c.onAction(PlanAction.New)
        (1..3).forEach { c.onAction(PlanAction.Retailer("r$it")) }
        c.onAction(PlanAction.Retailer("r4")); assertEquals(3, c.state.value.draft.selections.size)
        assertTrue(c.state.value.errors.getValue("selections").contains("tối đa 3"))
    }
    @Test fun uncertainSaveRetriesIdenticalKeyBodyAndFreezesEdits() = runTest {
        val g = Gateway().apply { saveFailure = AuthFailure.Network() }; val c = controller(g); ready(c)
        c.onAction(PlanAction.Save); val request = c.state.value.pending!!
        c.onAction(PlanAction.Budget("900000")); c.onAction(PlanAction.Category("c2"))
        assertEquals("500000", c.state.value.draft.budget); assertEquals(listOf("c1"), c.state.value.draft.categoryIds)
        g.saveFailure = null; c.onAction(PlanAction.Save)
        assertEquals(listOf(request, request), g.requests); assertEquals(PlanStep.DETAIL, c.state.value.step); assertNull(c.state.value.pending)
    }
    @Test fun doubleSubmitDoesNotStartSecondRequestAndClearCancelsTheFirst() = runTest {
        val g = Gateway().apply { saveGate = CompletableDeferred() }; val c = controller(g); ready(c)
        c.onAction(PlanAction.Save); c.onAction(PlanAction.Save)
        assertTrue(c.state.value.saving); assertEquals(1, g.requests.size)
        c.clear(); g.saveGate!!.complete(Unit); runCurrent()
        assertEquals(PlanStep.CLOSED, c.state.value.step); assertNull(c.state.value.saved)
    }
    @Test fun definitiveFieldErrorKeepsInputsAndRoutesToRelevantStep() = runTest {
        val g = Gateway().apply { saveFailure = AuthFailure.Http(422, "VALIDATION_ERROR", mapOf("selections" to "UNKNOWN_REFERENCE"), null) }
        val c = controller(g); ready(c); c.onAction(PlanAction.Save)
        assertEquals(PlanStep.STORES, c.state.value.step); assertEquals("500000", c.state.value.draft.budget)
        assertEquals(listOf("c1"), c.state.value.draft.categoryIds); assertNull(c.state.value.pending)
        assertTrue("selections" in c.state.value.errors)
    }
    @Test fun conflictDoesNotOverwriteAndExplicitReloadReadsNewVersion() = runTest {
        val g = Gateway(); val c = controller(g)
        c.onAction(PlanAction.Open("plan-one")); c.onAction(PlanAction.Edit)
        c.onAction(PlanAction.Budget("600000")); c.onAction(PlanAction.Continue); c.onAction(PlanAction.Continue)
        g.saveFailure = AuthFailure.Http(409, "VERSION_CONFLICT", emptyMap(), null)
        c.onAction(PlanAction.Save); assertTrue(c.state.value.conflict); assertEquals("600000", c.state.value.draft.budget)
        c.onAction(PlanAction.CancelConflict); assertEquals("600000", c.state.value.draft.budget)
        g.saved = samplePlan(version = 2, budget = "700000")
        c.onAction(PlanAction.ReloadConflict)
        assertEquals("700000", c.state.value.draft.budget); assertEquals(2, c.state.value.saved!!.plan.version)
    }
    @Test fun editingUsesPlansOriginalLimitInsteadOfChangedProfileDefault() = runTest {
        val g = Gateway().apply { max = 1; saved = samplePlan(max = 3) }; val c = controller(g)
        c.onAction(PlanAction.Open("plan-one")); c.onAction(PlanAction.Edit)
        assertEquals(3, c.state.value.maxStores)
        c.onAction(PlanAction.Retailer("r2")); assertEquals(2, c.state.value.draft.selections.size)
    }
    @Test fun referenceRetryKeepsTypedBudgetAndUnknownLimitsBlockSelection() = runTest {
        val g = Gateway().apply { refsFailure = AuthFailure.Network() }; val c = controller(g)
        c.onAction(PlanAction.New); c.onAction(PlanAction.Budget("750000"))
        assertNotNull(c.state.value.referenceError); assertNull(c.state.value.maxStores)
        g.refsFailure = null; c.onAction(PlanAction.RetryReferences)
        assertNull(c.state.value.referenceError); assertEquals("750000", c.state.value.draft.budget)
        assertEquals(3, c.state.value.maxStores)
    }
    @Test fun changedBranchQueryDropsOldCursorBeforeSearching() = runTest {
        val g = Gateway(); val c = controller(g); c.onAction(PlanAction.New); c.onAction(PlanAction.Branches("r1"))
        assertNotNull(c.state.value.branchCursor)
        c.onAction(PlanAction.BranchQuery("new area")); c.onAction(PlanAction.MoreBranches)
        assertEquals(1, g.queries.size); assertNull(c.state.value.branchCursor)
        c.onAction(PlanAction.SearchBranches); assertEquals(Triple("r1", "new area", null), g.queries.last())
    }
    @Test fun chainFilterIsUiStateAndDoesNotChangeSelectedInputs() = runTest {
        val c = controller(); ready(c); c.onAction(PlanAction.Back)
        val original = c.state.value.draft
        c.onAction(PlanAction.RetailerQuery("Chain 2")); c.onAction(PlanAction.Continue); c.onAction(PlanAction.Back)
        assertEquals("Chain 2", c.state.value.retailerQuery); assertEquals(original, c.state.value.draft)
        assertFalse(JSONObject(c.state.value.draft.body()).has("q"))
    }
    @Test fun rateLimitBlocksRetryAndKeepsOriginalIntention() = runTest {
        val g = Gateway().apply { saveFailure = AuthFailure.Http(429, "RATE_LIMITED", emptyMap(), 2) }; val c = controller(g); ready(c)
        c.onAction(PlanAction.Save); val request = c.state.value.pending
        c.onAction(PlanAction.Save); assertEquals(1, g.requests.size)
        advanceTimeBy(2100); runCurrent(); assertEquals(0, c.state.value.retrySeconds)
        g.saveFailure = null; c.onAction(PlanAction.Save); assertEquals(request, g.requests.last())
    }
    @Test fun sessionExpiryAndOwnerSwitchClearAllPrivateScreenState() = runTest {
        val g = Gateway(); var ended = 0; val c = controller(g) { ended++ }
        c.home("owner-a"); ready(c)
        g.saveFailure = AuthFailure.SessionEnded(); c.onAction(PlanAction.Save)
        assertEquals(1, ended); assertEquals(PlanUiState(), c.state.value)
        g.saveFailure = null; g.pages = emptyList(); c.home("owner-b")
        assertTrue(c.state.value.plans.isEmpty()); assertTrue(c.state.value.draft.selections.isEmpty())
    }
}
