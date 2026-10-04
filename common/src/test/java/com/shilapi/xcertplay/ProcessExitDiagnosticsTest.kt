package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessExitDiagnosticsTest {
    @Test fun androidNineDoesNotQueryTheAndroidElevenService() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.ACTIVITY_SERVICE) fail("API 28 must not query exit history")
                return super.getSystemService(name)
            }
        }
        assertEquals("Process exits available=false requiresApi=30", ProcessExitDiagnostics.report(context))
    }

    @Test fun exitHistoryIsBoundedAndReportsDistinctCausesWithoutIdentifiers() {
        val records = listOf(4, 5, 3, 10).map {
            ProcessExitDiagnostics.Record(900, it, 9, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, 2048, 4096)
        }
        val report = ProcessExitDiagnostics.report(30, 1000) { records }
        assertTrue(report.contains("count=3"))
        assertTrue(report.contains("ageMs=100 reason=java_crash"))
        assertTrue(report.contains("reason=native_crash"))
        assertTrue(report.contains("reason=low_memory"))
        assertFalse(report.contains("user_requested"))
        assertFalse(report.contains("index=3"))
        report.lineSequence().forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }
    }

    @Test fun unavailableOrBrokenFirmwareCannotBreakReportExportOrLeakErrorMessages() {
        for (failure in listOf(SecurityException("private phone token=secret"), NoSuchMethodError("secret=private"))) {
            val report = ProcessExitDiagnostics.report(30, 1000) { throw failure }
            assertTrue(report.contains("available=false"))
            assertTrue(report.contains(failure.javaClass.simpleName))
            assertFalse(report.contains("secret"))
            assertFalse(report.contains("private"))
            assertEquals(report, DiagnosticRedactor.redact(report))
        }
    }

    @Test @Config(sdk = [30]) fun onlyOwnPackageAndThreeRecentExitsAreRequested() {
        val app = RuntimeEnvironment.getApplication()
        val manager = mock(ActivityManager::class.java)
        `when`(manager.getHistoricalProcessExitReasons(app.packageName, 0, 3)).thenReturn(emptyList())
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ACTIVITY_SERVICE) manager else super.getSystemService(name)
        }
        assertTrue(ProcessExitDiagnostics.report(context).contains("available=true count=0"))
        verify(manager).getHistoricalProcessExitReasons(app.packageName, 0, 3)
        verifyNoMoreInteractions(manager)
    }

    @Test fun missingFutureTimestampsAndUnknownReasonsRemainUnknown() {
        val records = listOf(0L, 2000L).map { ProcessExitDiagnostics.Record(it, 1234, 0, 0, -1, -1) }
        val report = ProcessExitDiagnostics.report(30, 1000) { records }
        assertEquals(2, report.lineSequence().count { it.contains("ageMs=unknown reason=unknown reasonCode=1234") })
        assertTrue(report.contains("pssKiB=0 rssKiB=0"))
        assertFalse(report.contains("ageMs=-"))
    }
}
