package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class ClusterSongWriteResultTest {
    @Test fun acceptsCompleteNormalAndObservedVendorWrites() {
        assertTrue(ClusterSongWriteResult.accepted("source=0\nstate=0\ntext=0"))
        assertTrue(ClusterSongWriteResult.accepted(
            "source=-2147482648\nstate=-2147482648\ntext=-2147482648"))
        assertTrue(ClusterSongWriteResult.accepted("source=0\nstate=-2147482648\ntext=0"))
    }

    @Test fun clearRequiresTheStateResultWithoutPretendingTheOtherFieldsWereWritten() {
        assertTrue(ClusterSongWriteResult.accepted("state=0", clearing = true))
        assertTrue(ClusterSongWriteResult.accepted("state=-2147482648", clearing = true))
        assertFalse(ClusterSongWriteResult.accepted("state=0"))
        assertFalse(ClusterSongWriteResult.accepted("source=0\nstate=0\ntext=0", clearing = true))
    }

    @Test fun emptyPartialDuplicateAndMalformedResponsesDoNotCacheAnUnwrittenSong() {
        for (output in listOf("", "shell died", "source=0\nstate=0", "source=0\nstate=0\ntext=ERR too long",
            "source=0\nstate=0\ntext=0\ntext=0", "source=0\nstate=0\ntext=0\nwrite=ERR Permission denied")) {
            assertFalse(output, ClusterSongWriteResult.accepted(output))
        }
    }

    @Test fun unrelatedVendorErrorsRemainFailures() {
        assertFalse(ClusterSongWriteResult.accepted("source=0\nstate=-1\ntext=0"))
        assertFalse(ClusterSongWriteResult.accepted("source=0\nstate=0\ntext=-2147482647"))
        assertFalse(ClusterSongWriteResult.accepted("exit=-2147482648"))
    }
}
