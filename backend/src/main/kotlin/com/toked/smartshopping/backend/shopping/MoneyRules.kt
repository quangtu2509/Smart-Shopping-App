package com.toked.smartshopping.backend.shopping

import com.toked.smartshopping.backend.ApiFailure
import com.toked.smartshopping.backend.FieldError
import java.math.BigDecimal
import java.math.RoundingMode

internal fun invalid(field: String, code: String): Nothing = throw ApiFailure(422, "VALIDATION_ERROR", listOf(FieldError(field, code)))

/** Currency policy is explicit; adding a currency never enables conversion. */
class MoneyRules(private val scales: Map<String, Int> = mapOf("VND" to 0)) {
    init { require(scales.isNotEmpty() && scales.all { it.key.matches(Regex("[A-Z]{3}")) && it.value in 0..6 }) }

    fun decimal(value: String, field: String): BigDecimal {
        if (value.length > 128 || !value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) invalid(field, "INVALID_DECIMAL")
        return value.toBigDecimalOrNull() ?: invalid(field, "INVALID_DECIMAL")
    }
    fun amount(value: String, currency: String, field: String = "price", positive: Boolean = false): BigDecimal {
        val scale = scales[currency] ?: invalid("currency", "UNSUPPORTED_CURRENCY")
        val parsed = decimal(value, field)
        if (positive && parsed.signum() <= 0) invalid(field, "MUST_BE_POSITIVE")
        return try { parsed.setScale(scale, RoundingMode.UNNECESSARY) }
        catch (_: ArithmeticException) { invalid(field, "INVALID_PRECISION") }
    }
    fun quantity(value: String, step: String): BigDecimal {
        val q = decimal(value, "quantity")
        val s = decimal(step, "quantity_step")
        if (q.signum() <= 0 || s.signum() <= 0) invalid("quantity", "MUST_BE_POSITIVE")
        if (q.remainder(s).signum() != 0) invalid("quantity", "INVALID_STEP")
        return q
    }
    fun subtotal(price: String?, quantity: String, currency: String): String? {
        // Validate quantity even when price is missing; null only describes the price.
        val q = decimal(quantity, "quantity")
        if (q.signum() <= 0) invalid("quantity", "MUST_BE_POSITIVE")
        if (price == null) { if (currency !in scales) invalid("currency", "UNSUPPORTED_CURRENCY"); return null }
        return amount(price, currency).multiply(q).setScale(scales.getValue(currency), RoundingMode.HALF_UP).toPlainString()
    }
    fun summary(budget: String, currency: String, lines: List<Pair<String, String?>>): BudgetSummary {
        val limit = amount(budget, currency, "budget", positive = true)
        var total = BigDecimal.ZERO.setScale(scales.getValue(currency))
        val missing = mutableListOf<String>()
        for ((id, subtotal) in lines) {
            if (subtotal == null) missing += id else total = total.add(amount(subtotal, currency, "subtotal"))
        }
        val remaining = limit.subtract(total)
        val over = total.subtract(limit).max(BigDecimal.ZERO).setScale(scales.getValue(currency))
        val warnings = buildList {
            if (missing.isNotEmpty()) add(ShoppingWarning("UNKNOWN_PRICE", "Some item prices are unknown.", missing))
            if (over.signum() > 0) add(ShoppingWarning("OVER_BUDGET", "Known subtotal exceeds the budget."))
        }
        return BudgetSummary(currency, limit.toPlainString(), total.toPlainString(), remaining.toPlainString(), missing.isEmpty(), missing, over.toPlainString(), warnings)
    }
    fun sessionSummary(budget: String, currency: String, items: List<SessionItemSnapshot>, purchasedOnly: Boolean = false): BudgetSummary {
        val eligible = items.filter { item -> when (item.status) {
            ItemOutcome.PURCHASED -> true
            ItemOutcome.PENDING -> !purchasedOnly
            ItemOutcome.REMOVED_BY_USER, ItemOutcome.REPLACED, ItemOutcome.SKIPPED_AT_COMPLETION -> false
        } }
        val lines = eligible.map { item ->
            val offer = item.selected_price_snapshot
            if (offer != null && offer.currency != currency) invalid("currency", "CURRENCY_MISMATCH")
            item.id to subtotal(offer?.price, item.quantity, currency)
        }
        return summary(budget, currency, lines)
    }
}
