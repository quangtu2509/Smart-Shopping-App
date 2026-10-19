package com.toked.smartshopping.backend

import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalConfigurationScriptTest {
    @Test fun configureAndRunKeepSenderAndPrivateConfigurationCompatible() {
        val builder = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "src/test/powershell/run-local.tests.ps1")
            .redirectErrorStream(true).redirectOutput(File("build/local-configuration-tests.log"))
        // A daemon launched by PowerShell 7 may carry modules incompatible with Windows PowerShell 5.1.
        builder.environment().remove("PSModulePath")
        val process = builder.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "Local configuration fixture tests timed out")
        assertEquals(0, process.exitValue(), "Local configuration tests failed; inspect bounded diagnostics locally")
    }
}
