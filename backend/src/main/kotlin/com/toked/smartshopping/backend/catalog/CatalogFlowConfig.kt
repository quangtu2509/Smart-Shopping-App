package com.toked.smartshopping.backend.catalog

import java.net.URI
import java.time.Duration

class CatalogFlowConfig private constructor(
    val origin: URI, internal val apiKey: String, val timeout: Duration, val maxResponseBytes: Int,
) {
    override fun toString(): String = "CatalogFlowConfig(credentials=redacted)"

    companion object {
        const val DEFAULT_ORIGIN = "https://crawl-data-rosy.vercel.app"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CatalogFlowConfig? {
            val key = environment["CATALOGFLOW_API_KEY"]
            val origin = environment["CATALOGFLOW_BASE_URL"]
            if (key.isNullOrBlank() && origin.isNullOrBlank()) return null
            require(!key.isNullOrBlank()) { "CATALOGFLOW_API_KEY is required when configuring the provider" }
            return create(origin ?: DEFAULT_ORIGIN, key)
        }

        fun create(origin: String, apiKey: String): CatalogFlowConfig = validated(origin, apiKey, Duration.ofSeconds(20), 4 * 1024 * 1024, false)

        // HTTP can only be enabled by test code in this module, and only for literal loopback.
        internal fun fixture(origin: String, apiKey: String, timeout: Duration = Duration.ofSeconds(2), maxBytes: Int = 4096): CatalogFlowConfig =
            validated(origin, apiKey, timeout, maxBytes, true)

        private fun validated(origin: String, apiKey: String, timeout: Duration, maxBytes: Int, fixture: Boolean): CatalogFlowConfig {
            val uri = try { URI(origin) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid CatalogFlow origin") }
                catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid CatalogFlow origin") }
            val secure = uri.scheme == "https"
            val loopback = fixture && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]")
            require((secure || loopback) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/") &&
                (uri.port == -1 || uri.port in 1..65535)) { "CatalogFlow requires an HTTPS origin without credentials, path or query" }
            require(apiKey.length in 1..8192 && apiKey.all { it.code in 33..126 }) { "Invalid CatalogFlow API key format" }
            require(!timeout.isNegative && !timeout.isZero && maxBytes in 1..16 * 1024 * 1024)
            return CatalogFlowConfig(URI(uri.scheme, null, uri.host, uri.port, null, null, null), apiKey, timeout, maxBytes)
        }
    }
}
