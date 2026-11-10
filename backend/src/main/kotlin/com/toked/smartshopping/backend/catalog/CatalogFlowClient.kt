package com.toked.smartshopping.backend.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One application-scoped HTTP client. No cache, retries, background crawler or database work. */
class CatalogFlowClient(private val config: CatalogFlowConfig, private val clock: Clock = Clock.systemUTC()) : IncrementalCatalogProvider, AutoCloseable {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(config.timeout).build()
    private val decoder = CatalogFlowDecoder()

    override suspend fun products(query: CatalogQuery): ProviderPage {
        val params = linkedMapOf("page" to query.page.toString(), "limit" to query.limit.toString())
        listOf("search" to query.search, "site" to query.site, "collection" to query.collection, "category" to query.category)
            .forEach { (key, value) -> if (!value.isNullOrBlank()) params[key] = value }
        return decoder.page(request("/api/v1/live-products", params), query)
    }

    override suspend fun productSnapshot(after: Long, baseline: String?): ProviderSnapshot {
        require(after >= 0 && (baseline == null || baseline.length <= 512))
        val params = linkedMapOf("after" to after.toString(), "limit" to "100", "approved_only" to "1")
        if (baseline != null) params["baseline"] = baseline
        return decoder.snapshot(request("/api/v1/product-snapshot", params))
    }

    override suspend fun productChanges(cursor: String): ProviderChanges {
        require(cursor.length in 1..512)
        return decoder.changes(request("/api/v1/product-changes", linkedMapOf("cursor" to cursor, "limit" to "100", "approved_only" to "1")))
    }

    private suspend fun request(path: String, params: Map<String, String>): String {
        val encoded = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("${config.origin}$path?$encoded"))
            .GET().timeout(config.timeout).header("Accept", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}").build()
        val body = runInterruptible(Dispatchers.IO) {
            val future = client.sendAsync(request) { info ->
                val failure = statusFailure(info.statusCode(), info.headers().firstValue("Retry-After").orElse(null))
                if (failure != null) FailedBody(failure)
                else if (info.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase() !in
                    setOf("application/json", "application/problem+json")) FailedBody(ProviderFailure(ProviderFailureCode.INVALID_RESPONSE))
                else LimitedBody(config.maxResponseBytes)
            }
            try { future.get(config.timeout.toMillis(), TimeUnit.MILLISECONDS).body() }
            catch (_: TimeoutException) { throw ProviderFailure(ProviderFailureCode.TIMEOUT, retryable = true) }
            catch (failure: ExecutionException) { throw transportFailure(failure.cause) }
            finally { if (!future.isDone) future.cancel(true) }
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE) }
        return text
    }

    private fun transportFailure(cause: Throwable?): ProviderFailure {
        var current = cause
        repeat(8) {
            if (current is ProviderFailure) return current as ProviderFailure
            if (current is BodyLimitExceeded) return ProviderFailure(ProviderFailureCode.RESPONSE_TOO_LARGE)
            if (current is java.net.http.HttpTimeoutException) return ProviderFailure(ProviderFailureCode.TIMEOUT, retryable = true)
            current = current?.cause
        }
        return ProviderFailure(ProviderFailureCode.NETWORK, retryable = true)
    }

    private fun statusFailure(status: Int, retry: String?): ProviderFailure? = when (status) {
        200 -> null
        401 -> ProviderFailure(ProviderFailureCode.AUTHENTICATION, upstreamStatus = status)
        409 -> ProviderFailure(ProviderFailureCode.DATASET_RESET, upstreamStatus = status)
        403 -> ProviderFailure(ProviderFailureCode.FORBIDDEN, upstreamStatus = status)
        429 -> ProviderFailure(ProviderFailureCode.RATE_LIMITED, retryable = true, retryAfterSeconds = retryAfter(retry), upstreamStatus = status)
        in 300..399 -> ProviderFailure(ProviderFailureCode.REDIRECT, upstreamStatus = status)
        in 500..599 -> ProviderFailure(ProviderFailureCode.UNAVAILABLE, retryable = true, retryAfterSeconds = retryAfter(retry), upstreamStatus = status)
        else -> ProviderFailure(ProviderFailureCode.INVALID_RESPONSE, upstreamStatus = status)
    }

    private fun retryAfter(value: String?): Int? {
        if (value == null) return null
        val seconds = value.toLongOrNull() ?: try {
            val until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val duration = Duration.between(clock.instant(), until)
            (duration.toMillis().coerceAtLeast(0) + 999) / 1000
        } catch (_: Exception) { return null }
        return seconds.takeIf { it >= 0 }?.coerceAtMost(86400)?.toInt()
    }

    // Backend runs on JDK 25. Close at application stop, cancelling outstanding requests first.
    override fun close() { client.shutdownNow(); client.close() }

    private class BodyLimitExceeded : IOException("Provider body limit exceeded")
    private class FailedBody(private val failure: ProviderFailure) : HttpResponse.BodySubscriber<ByteArray> {
        override fun getBody(): CompletionStage<ByteArray> = java.util.concurrent.CompletableFuture.failedFuture(failure)
        override fun onSubscribe(subscription: Flow.Subscription) = subscription.cancel()
        override fun onNext(item: List<ByteBuffer>) = Unit
        override fun onError(throwable: Throwable) = Unit
        override fun onComplete() = Unit
    }
    private class LimitedBody(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate = HttpResponse.BodySubscribers.ofByteArray()
        private var received = 0L
        private var subscription: Flow.Subscription? = null
        private var finished = false
        override fun getBody(): CompletionStage<ByteArray> = delegate.body
        override fun onSubscribe(subscription: Flow.Subscription) { this.subscription = subscription; delegate.onSubscribe(subscription) }
        override fun onNext(item: List<ByteBuffer>) {
            if (finished) return
            received += item.sumOf { it.remaining().toLong() }
            if (received > maxBytes) {
                finished = true
                subscription?.cancel()
                delegate.onError(BodyLimitExceeded())
            } else delegate.onNext(item)
        }
        override fun onError(throwable: Throwable) { if (!finished) { finished = true; delegate.onError(throwable) } }
        override fun onComplete() { if (!finished) { finished = true; delegate.onComplete() } }
    }
}
