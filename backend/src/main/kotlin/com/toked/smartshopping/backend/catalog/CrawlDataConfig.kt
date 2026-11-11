package com.toked.smartshopping.backend.catalog

import java.net.URI
import java.time.Duration

/**
 * Self-hosted CrawlData service. No credential: plain HTTP is accepted only for a loopback host, so a
 * keyless catalog cannot silently travel over an unencrypted network. Any other host requires HTTPS.
 */
class CrawlDataConfig private constructor(val origin: URI, val timeout: Duration, val maxResponseBytes: Int) {
    override fun toString(): String = "CrawlDataConfig(origin=$origin)"

    companion object {
        const val DEFAULT_ORIGIN = "http://127.0.0.1:8000"
        private val loopbackHosts = setOf("localhost", "127.0.0.1", "[::1]")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CrawlDataConfig =
            create(environment["CRAWLDATA_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ORIGIN)

        fun create(origin: String, timeout: Duration = Duration.ofSeconds(30), maxResponseBytes: Int = 8 * 1024 * 1024): CrawlDataConfig {
            val uri = try { URI(origin) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid CrawlData origin") }
            val host = uri.host?.lowercase()
            val allowedScheme = uri.scheme == "https" || (uri.scheme == "http" && host in loopbackHosts)
            require(allowedScheme && !host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && uri.rawPath in setOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) {
                "CrawlData requires HTTP on localhost or an HTTPS origin, without credentials, path or query"
            }
            require(!timeout.isNegative && !timeout.isZero && maxResponseBytes in 1..32 * 1024 * 1024)
            return CrawlDataConfig(URI(uri.scheme, null, host, uri.port, null, null, null), timeout, maxResponseBytes)
        }
    }
}
