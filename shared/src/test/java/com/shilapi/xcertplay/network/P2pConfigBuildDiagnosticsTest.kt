package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class P2pConfigBuildDiagnosticsTest {
    private val selection = P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5180)

    @Test fun successReturnsTheSameObjectAndIdentifiesTheCompletedBuilderStage() {
        val logs = mutableListOf<String>()
        val config = Any()
        val result = P2pConfigBuildDiagnostics.build(29, selection, logs::add) { config }

        assertSame(config, result)
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("stage=begin api=29 mode=FIXED_5_GHZ frequencyMHz=5180"))
        assertTrue(logs[1].contains("stage=complete"))
    }

    @Test fun linkageFailureIsPreservedAndReportsOneLocationWithoutPrivateMessages() {
        val logs = mutableListOf<String>()
        val failure = NoSuchMethodError("SSID=private passphrase=secret phone-token=hidden").apply {
            stackTrace = arrayOf(
                StackTraceElement("unrelated.private.Phone", "token", "private-path.txt", 8),
                StackTraceElement("android.net.wifi.p2p.WifiP2pConfig\$Builder", "build", "secret-file.java", 123),
                StackTraceElement("com.shilapi.xcertplay.network.WifiP2pGroupManager", "start", "file.java", 456),
            )
        }

        try {
            P2pConfigBuildDiagnostics.build(29, selection, logs::add) { throw failure }
            fail("Expected original linkage failure")
        } catch (actual: NoSuchMethodError) { assertSame(failure, actual) }

        val log = logs.last()
        assertTrue(log.contains("stage=failed api=29"))
        assertTrue(log.contains("failureClass=NoSuchMethodError causes=NoSuchMethodError"))
        assertTrue(log.contains("at=android.net.wifi.p2p.WifiP2pConfig\$Builder.build:123"))
        for (privateValue in listOf("private", "passphrase", "secret", "hidden", "start", "456", "java")) {
            assertFalse(log.contains(privateValue))
        }
    }

    @Test fun callbackFailuresDoNotAlterASuccessfulBuildOrItsOriginalFailure() {
        val config = Any()
        val brokenLog: (String) -> Unit = { throw AssertionError("broken logger") }
        assertSame(config, P2pConfigBuildDiagnostics.build(29, selection, brokenLog) { config })
        val original = IllegalArgumentException("original failure")
        try {
            P2pConfigBuildDiagnostics.build(29, selection, brokenLog) { throw original }
            fail("Expected original build failure")
        } catch (actual: IllegalArgumentException) { assertSame(original, actual) }
    }

    @Test fun cyclicCausesAndUnexpectedFrameTextProduceBoundedIdentifierOnlyOutput() {
        val logs = mutableListOf<String>()
        val first = IllegalStateException("private first")
        val second = IllegalArgumentException("private second")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = arrayOf(StackTraceElement(
            "android.net.wifi.p2p." + "B".repeat(1000) + "\nsecret", "method\n" + "x".repeat(1000), "private", -1,
        ))
        try {
            P2pConfigBuildDiagnostics.build(29, selection, logs::add) { throw first }
            fail("Expected original failure")
        } catch (actual: IllegalStateException) { assertSame(first, actual) }

        val log = logs.last()
        assertTrue(log.contains("causes=IllegalStateException/IllegalArgumentException"))
        assertTrue(log.length < 500)
        assertFalse(log.contains('\n'))
        assertFalse(log.contains("secret"))
        assertFalse(log.contains("private"))
    }

    @Test fun unrelatedExceptionMessagesAndConfigurationStringsAreNeverRead() {
        val config = object {
            override fun toString(): String = error("Configuration string must not be read")
        }
        assertSame(config, P2pConfigBuildDiagnostics.build(29, selection, {}) { config })
        val failure = object : RuntimeException() {
            override val message: String get() = error("Exception message must not be read")
        }
        try {
            P2pConfigBuildDiagnostics.build(29, selection, {}) { throw failure }
            fail("Expected original failure")
        } catch (actual: RuntimeException) { assertSame(failure, actual) }
    }

    @Test fun onlyTheReportedAndroidTenBuilderFailureIsEligible() {
        val matching = api29BuilderFailure()
        assertTrue(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(29, matching))
        for (sdk in listOf(28, 30, 33)) {
            assertFalse(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(sdk, matching))
        }
        val failures = listOf(
            RuntimeException(matching.message),
            NoSuchFieldError("getNetworkName() android.net.wifi.p2p.WifiP2pConfig"),
            api29BuilderFailure("getPassphrase() android.net.wifi.p2p.WifiP2pConfig"),
            api29BuilderFailure("getNetworkName() android.net.wifi.p2p.WifiP2pGroup"),
            api29BuilderFailure().apply {
                stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.network.WifiP2pGroupManager", "start", null, 1))
            },
        )
        for (failure in failures) assertFalse(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(29, failure))
    }

    @Test fun knownBuilderFailureBecomesTypedWithoutExposingItsPrivateMessage() {
        val logs = mutableListOf<String>()
        val original = api29BuilderFailure().apply {
            initCause(IllegalStateException("passphrase=secret"))
        }
        try {
            P2pConfigBuildDiagnostics.build(29, selection, logs::add) { throw original }
            fail("Expected typed compatibility failure")
        } catch (actual: P2pConfigBuildCompatibilityFailure) {
            assertSame(original, actual.cause)
            assertFalse(actual.message!!.contains("getNetworkName"))
        }
        assertTrue(logs.last().contains("stage=compatibility"))
        assertTrue(logs.last().contains("fallback=guarded_system_default"))
        for (log in logs) {
            assertFalse(log.contains("No virtual method"))
            assertFalse(log.contains("passphrase=secret"))
        }
    }

    @Test fun aDifferentReturnDescriptorDoesNotAuthorizeCompatibilityRecovery() {
        val wrongReturns = listOf("I", "Ljava/lang/Object;", "Ljava/lang/StringBuilder;")
        for (descriptor in wrongReturns) {
            val failure = api29BuilderFailure(
                "No virtual method getNetworkName()$descriptor in class Landroid/net/wifi/p2p/WifiP2pConfig;",
            )
            assertFalse(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(29, failure))
        }
    }

    @Test fun aFrameworkClassSuffixDoesNotMatchTheExactConfigurationDescriptor() {
        for (suffix in listOf("Compat", "\$Builder", "uration")) {
            val failure = api29BuilderFailure(
                "No virtual method getNetworkName()Ljava/lang/String; in class Landroid/net/wifi/p2p/WifiP2pConfig$suffix;",
            )
            assertFalse(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(29, failure))
        }
        val completeReportedMessage = api29BuilderFailure(
            "No virtual method getNetworkName()Ljava/lang/String; in class Landroid/net/wifi/p2p/WifiP2pConfig; " +
                "or its super classes (declaration of 'android.net.wifi.p2p.WifiP2pConfig' " +
                "appears in /system/framework/framework.jar!classes2.dex)",
        )
        assertTrue(P2pConfigBuildDiagnostics.isKnownApi29BuilderFailure(29, completeReportedMessage))
    }

    private fun api29BuilderFailure(
        message: String = "No virtual method getNetworkName()Ljava/lang/String; in class Landroid/net/wifi/p2p/WifiP2pConfig;",
    ) = NoSuchMethodError(message).apply {
        stackTrace = arrayOf(StackTraceElement("android.net.wifi.p2p.WifiP2pConfig\$Builder", "build", null, 1))
    }
}
