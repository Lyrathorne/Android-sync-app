package com.example.devicesync.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncPayloadsTest {
    @Test fun approvalRoundTrips() {
        val value = FolderPlanApprovedPayload("sync-1", listOf(FolderConflictResolutionPayload("docs/report.txt", "keep_both")))
        val json = ProtocolSerializer.payloadToJson(value)
        assertTrue("syncId" in json.toString())
        assertEquals(value, ProtocolSerializer.decodePayload<FolderPlanApprovedPayload>(json))
    }

    @Test fun folderOfferMetadataRoundTrips() {
        val value = FileOfferPayload("transfer-1", "report.txt", 12, "text/plain",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 65536, "sync-1", "docs/report.txt", true)
        assertEquals(value, ProtocolSerializer.decodePayload<FileOfferPayload>(ProtocolSerializer.payloadToJson(value)))
    }
}
