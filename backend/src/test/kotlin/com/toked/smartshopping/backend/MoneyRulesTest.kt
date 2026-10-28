package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.shopping.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import kotlin.test.*

class MoneyRulesTest {
    private val rules = MoneyRules()
    @Test fun rejectsInvalidDecimalsAndUnsupportedCurrencyWithoutRoundingInput() {
        for (input in listOf("-1", "NaN", "Infinity", "1e3", "1,000", "", "+1", " 1"))
            assertFailsWith<ApiFailure> { rules.amount(input, "VND") }
        assertFailsWith<ApiFailure> { rules.amount("1.1", "VND") }
        assertEquals("10", rules.amount("10.00", "VND").toPlainString())
        assertFailsWith<ApiFailure> { rules.amount("10", "USD") }
        assertFailsWith<ApiFailure> { rules.amount("0", "VND", "budget", positive = true) }
    }
    @Test fun quantityUsesExactStepsInsteadOfBinaryFloatingPoint() {
        assertEquals("0.3", rules.quantity("0.3", "0.1").toPlainString())
        for (value in listOf("0", "-1", "0.15")) assertFailsWith<ApiFailure> { rules.quantity(value,"0.1") }
        assertFailsWith<ApiFailure> { rules.quantity("1", "0") }
    }
    @Test fun roundsEachLineHalfUpBeforeSumming() {
        val line = rules.subtotal("1", "0.5", "VND")
        assertEquals("1", line)
        assertEquals("2", rules.summary("10", "VND", listOf("a" to line, "b" to line)).selected_total)
        val usd = MoneyRules(mapOf("USD" to 2))
        assertEquals("0.02", usd.subtotal("0.03", "0.5", "USD"))
        assertFailsWith<ApiFailure> { usd.amount("0.031", "USD") }
    }
    @Test fun missingIsDifferentFromConfirmedZeroAndOverBudgetIsOnlyWarning() {
        val partial = rules.summary("100", "VND", listOf("free" to "0", "unknown" to null, "paid" to "120"))
        assertEquals("120", partial.selected_total); assertEquals("-20", partial.remaining_budget)
        assertEquals("20", partial.over_budget_amount); assertFalse(partial.is_complete)
        assertEquals(listOf("unknown"), partial.missing_price_item_ids)
        assertEquals(setOf("UNKNOWN_PRICE","OVER_BUDGET"), partial.warnings.map { it.code }.toSet())
        assertTrue(rules.summary("100", "VND", listOf("free" to "0")).is_complete)
    }
    private fun sessionItem(id: String, status: ItemOutcome, price: String?) = SessionItemSnapshot(id,null,null,null,"Item",null,null,null,null,null,
        "1","piece",Priority.RECOMMENDED,price?.let { Offer(OfferRef(OfferKind.CATALOG,id),"1",price=it,currency="VND",price_scope=PriceScope.RETAILER) }, status,ItemOrigin.INITIAL_PLAN)
    @Test fun activeAndPurchasedTotalsUseOnlyTheirOwnOutcomeSets() {
        val items = listOf(sessionItem("pending",ItemOutcome.PENDING,"10"),sessionItem("purchased",ItemOutcome.PURCHASED,"20"),
            sessionItem("removed",ItemOutcome.REMOVED_BY_USER,"100"),sessionItem("replaced",ItemOutcome.REPLACED,"100"),
            sessionItem("skipped",ItemOutcome.SKIPPED_AT_COMPLETION,null),sessionItem("purchased-unknown",ItemOutcome.PURCHASED,null))
        assertEquals("30",rules.sessionSummary("100","VND",items).selected_total)
        val purchased = rules.sessionSummary("100","VND",items,purchasedOnly=true)
        assertEquals("20",purchased.selected_total); assertEquals(listOf("purchased-unknown"),purchased.missing_price_item_ids)
        assertEquals("80",purchased.remaining_budget)
    }
    @Test fun sharedWireFixtureRoundTripsAndMatchesServerCalculation() {
        val raw = File("../Frontend/app/src/test/resources/shopping/plan-view.json").readText()
        val codec = Json { encodeDefaults = true }
        val dto = codec.decodeFromString<PlanView>(raw)
        assertEquals("30",dto.items.first().subtotal)
        assertEquals(dto.plan.budget_summary, rules.summary(dto.plan.budget,"VND",dto.items.map { it.id to it.subtotal }))
        val encoded = codec.parseToJsonElement(codec.encodeToString(dto)).jsonObject
        assertTrue(encoded["plan"]!!.jsonObject["budget"]!!.jsonPrimitive.isString)
        assertTrue(encoded["items"]!!.jsonArray[0].jsonObject["quantity"]!!.jsonPrimitive.isString)
        assertEquals(JsonNull,encoded["items"]!!.jsonArray[1].jsonObject["subtotal"])
        assertEquals(dto,codec.decodeFromString<PlanView>(codec.encodeToString(dto)))
    }
}
