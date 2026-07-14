package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable data class FolderManifestEntryPayload(val relativePath: String, val sizeBytes: Long, val lastModifiedUtc: String, val sha256: String)
@Serializable data class FolderManifestPayload(val syncId: String, val rootId: String, val generatedAtUtc: String, val entries: List<FolderManifestEntryPayload>)
@Serializable data class FolderPlanOperationPayload(val relativePath: String, val action: String, val reason: String? = null)
@Serializable data class FolderPlanPayload(val syncId: String, val operations: List<FolderPlanOperationPayload>)
@Serializable data class FolderConflictResolutionPayload(val relativePath: String, val resolution: String)
@Serializable data class FolderPlanApprovedPayload(val syncId: String, val conflictResolutions: List<FolderConflictResolutionPayload>)
