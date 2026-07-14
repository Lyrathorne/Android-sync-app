package com.example.devicesync.core.foldersync

import com.example.devicesync.core.protocol.FolderManifestEntryPayload
import com.example.devicesync.core.protocol.FolderManifestPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncPlannerTest {
    @Test fun planDoesNotSilentlyOverwriteOrDelete() {
        val local = manifest(entry("local.txt", "a"), entry("same.txt", "same"), entry("conflict.txt", "left"))
        val remote = manifest(entry("remote.txt", "b"), entry("same.txt", "same"), entry("conflict.txt", "right"))
        val plan = FolderSyncPlanner.build(local, remote)
        assertTrue(plan.operations.any { it.relativePath == "local.txt" && it.action == "upload" })
        assertTrue(plan.operations.any { it.relativePath == "remote.txt" && it.action == "download" })
        assertTrue(plan.operations.any { it.relativePath == "conflict.txt" && it.action == "conflict" })
        assertFalse(plan.operations.any { it.action == "delete" || it.relativePath == "same.txt" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafePathIsRejected() { FolderSyncPlanner.normalize("../secret.txt") }

    @Test(expected = IllegalArgumentException::class)
    fun windowsDriveSyntaxIsRejected() { FolderSyncPlanner.normalize("folder/C:secret.txt") }

    private fun manifest(vararg entries: FolderManifestEntryPayload) = FolderManifestPayload(
        "sync-1", "root", "2026-07-14T00:00:00Z", entries.toList(),
    )
    private fun entry(path: String, hash: String) = FolderManifestEntryPayload(path, 1, "2026-07-14T00:00:00Z", hash)
}
