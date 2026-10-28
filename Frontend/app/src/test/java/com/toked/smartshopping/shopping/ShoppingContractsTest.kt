package com.toked.smartshopping.shopping

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ShoppingContractsTest {
    private fun fixture() = checkNotNull(javaClass.classLoader!!.getResourceAsStream("shopping/plan-view.json")).bufferedReader().use { it.readText() }
    @Test fun sharedBackendFixturePreservesMoneyQuantityMetadataAndUnknownPrice() {
        val data=ShoppingContracts.decodePlanView(fixture())
        assertEquals("30",data.plan.budgetSummary.selectedTotal.amount.toPlainString())
        assertEquals("1.5",data.items.first().quantity.value)
        assertEquals("20",data.items.first().selectedPriceSnapshot!!.price!!.amount.toPlainString())
        assertEquals("Fixture product",data.items.first().selectedPriceSnapshot!!.product!!.name)
        assertEquals("RETAILER",data.items.first().selectedPriceSnapshot!!.priceScope)
        assertNull(data.items.last().subtotal);assertFalse(data.plan.budgetSummary.complete)
        assertEquals(listOf("unknown-item"),data.plan.budgetSummary.missingPriceItemIds)
    }
    @Test fun amountsBeyondDoubleIntegerPrecisionRemainExact() {
        val json=JSONObject(fixture());val p=json.getJSONObject("plan")
        p.put("budget","9007199254740993");p.getJSONObject("budget_summary").put("budget","9007199254740993")
        assertEquals("9007199254740993",ShoppingContracts.decodePlanView(json.toString()).plan.budget.amount.toPlainString())
    }
    @Test fun negativeRemainingBudgetIsAValidServerValue() {
        val json=JSONObject(fixture());val summary=json.getJSONObject("plan").getJSONObject("budget_summary")
        summary.put("selected_total","120").put("remaining_budget","-20").put("over_budget_amount","20")
        assertEquals("-20",ShoppingContracts.decodePlanView(json.toString()).plan.budgetSummary.remainingBudget.amount.toPlainString())
    }
    @Test fun rejectsNumericWireValuesInsteadOfSilentlyConvertingThroughDouble() {
        val json=JSONObject(fixture());json.getJSONObject("plan").put("budget",100)
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(json.toString()) }
        val quantity=JSONObject(fixture());quantity.getJSONArray("items").getJSONObject(0).put("quantity",1.5)
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(quantity.toString()) }
    }
    @Test fun rejectsFalseCompletenessCurrencyMismatchAndUnsupportedState() {
        val complete=JSONObject(fixture());complete.getJSONObject("plan").getJSONObject("budget_summary").put("is_complete",true)
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(complete.toString()) }
        val currency=JSONObject(fixture());currency.getJSONArray("items").getJSONObject(0).getJSONObject("selected_price_snapshot").put("currency","USD")
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(currency.toString()) }
        val state=JSONObject(fixture());state.getJSONObject("plan").put("status","UNKNOWN_STATE")
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(state.toString()) }
    }
    @Test fun rejectsZeroQuantityAndMalformedResponses() {
        assertThrows(IllegalArgumentException::class.java) { Quantity.parse("0") }
        assertThrows(IllegalArgumentException::class.java) { Quantity.parse("1e3") }
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView("{}") }
    }
    @Test fun rejectsCoercedVersionAndMissingRequiredNullableFields() {
        val version=JSONObject(fixture());version.getJSONObject("plan").put("version","4")
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(version.toString()) }
        val price=JSONObject(fixture());price.getJSONArray("items").getJSONObject(0).getJSONObject("selected_price_snapshot").remove("price")
        assertThrows(InvalidShoppingResponse::class.java) { ShoppingContracts.decodePlanView(price.toString()) }
    }
}
