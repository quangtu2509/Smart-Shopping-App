package com.toked.smartshopping.auth

import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry

/** Full visible-window evidence. Avoids headless-emulator PixelCopy timeouts in Compose capture. */
internal fun captureFixtureScreenshot(): Bitmap {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(100,5000)
    return checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Device did not capture the visible fixture" }
}

/** Only works against the isolated test executable, which never sends real email. */
internal fun fixtureCode(base: String, email: String): String {
    val root = base.trimEnd('/').removeSuffix("/api/v1")
    require(root.startsWith("http://127.0.0.1:"))
    val connection = URL("$root/fixture/code?email=${android.net.Uri.encode(email)}").openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    return try { connection.inputStream.bufferedReader().use { it.readText() }.also { require(it.matches(Regex("[0-9]{6}"))) } }
        finally { connection.disconnect() }
}

internal suspend fun fixtureRegister(api: AuthApi, base: String, email: String, password: String): AuthSession {
    try { api.register(email,password,password); error("Registration must require proof") }
    catch (step: AuthFailure.NextStep) {
        return api.verifyEmail(step.challenge.id,fixtureCode(base,email))
    }
}
