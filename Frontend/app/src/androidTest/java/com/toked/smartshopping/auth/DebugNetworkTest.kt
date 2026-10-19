package com.toked.smartshopping.auth

import android.security.NetworkSecurityPolicy
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class DebugNetworkTest {
    @Test fun debugPolicyPermitsOnlyExactLocalHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        listOf("127.0.0.1", "localhost", "10.0.2.2").forEach { host ->
            assertTrue("Debug HTTP should permit $host", policy.isCleartextTrafficPermitted(host))
        }
        listOf("example.com", "10.0.2.20", "10.0.2.2.example.com", "192.168.1.10").forEach { host ->
            assertFalse("Debug HTTP must reject $host", policy.isCleartextTrafficPermitted(host))
        }
    }

    /** Opt-in, read-only reachability check. No credentials, account creation or database fixtures. */
    @Test fun emulatorReachesHostBackendWithoutAuthorization() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localBackendProbe") == "true")
        val url = URL(BuildConfig.AUTH_API_BASE_URL.trimEnd('/') + "/me")
        assertEquals("10.0.2.2", url.host)
        assertEquals(8080, url.port)
        assertEquals("/api/v1/me", url.path)
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        try {
            assertEquals(401, connection.responseCode)
            val body = connection.errorStream.bufferedReader().use { it.readText() }
            assertEquals("AUTH_REQUIRED", org.json.JSONObject(body).getJSONObject("error").getString("code"))
        } finally {
            connection.disconnect()
        }
    }
}
