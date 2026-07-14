package com.example.devicesync.core.foldersync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.devicesync.core.network.SharingMessageListener
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.*
import com.example.devicesync.core.security.Base64Url
import com.example.devicesync.core.transfer.AndroidFolderTransferMetadata
import com.example.devicesync.core.transfer.PersistentOutgoingTransferQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object FolderConflictResolutions {
    const val KEEP_WINDOWS = "keep_windows"
    const val KEEP_ANDROID = "keep_android"
    const val KEEP_BOTH = "keep_both"
    val all = setOf(KEEP_WINDOWS, KEEP_ANDROID, KEEP_BOTH)
}

object FolderSyncPlanner {
    fun build(local: FolderManifestPayload, remote: FolderManifestPayload): FolderPlanPayload {
        require(local.syncId == remote.syncId)
        val left = local.entries.associateBy { normalize(it.relativePath) }
        val right = remote.entries.associateBy { normalize(it.relativePath) }
        val operations = (left.keys + right.keys).toSortedSet().mapNotNull { path ->
            val localEntry = left[path]
            val remoteEntry = right[path]
            when {
                localEntry != null && remoteEntry == null -> FolderPlanOperationPayload(path, "upload")
                localEntry == null && remoteEntry != null -> FolderPlanOperationPayload(path, "download")
                localEntry?.sha256 != remoteEntry?.sha256 -> FolderPlanOperationPayload(path, "conflict", "both_modified")
                else -> null
            }
        }
        return FolderPlanPayload(local.syncId, operations)
    }

    fun normalize(path: String): String {
        val value = path.replace('\\', '/')
        val segments = value.split('/')
        require(!value.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            segments.none { it.isEmpty() || it == "." || it == ".." || ':' in it || '\u0000' in it }) { "Unsafe relative path." }
        return segments.joinToString("/")
    }
}

class SafFolderManifestBuilder(private val resolver: ContentResolver) {
    suspend fun build(treeUri: String, syncId: String): FolderManifestPayload = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val rootDocumentId = DocumentsContract.getTreeDocumentId(tree)
        val entries = mutableListOf<FolderManifestEntryPayload>()
        visit(tree, rootDocumentId, "", entries)
        FolderManifestPayload(syncId, Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(treeUri.encodeToByteArray())),
            Instant.now().toString(), entries.sortedBy { it.relativePath })
    }

    fun findDocument(treeUri: String, relativePath: String): Uri? {
        val tree = Uri.parse(treeUri)
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (segment in FolderSyncPlanner.normalize(relativePath).split('/')) {
            val child = findChild(tree, parentId, segment) ?: return null
            parentId = child.first
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
    }

    fun createIncomingTarget(treeUri: String, relativePath: String, transferId: String, mimeType: String,
        replaceExisting: Boolean, conflictCopy: Boolean): FolderIncomingTarget {
        val tree = Uri.parse(treeUri)
        val segments = FolderSyncPlanner.normalize(relativePath).split('/')
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (segment in segments.dropLast(1)) {
            val existing = findChild(tree, parentId, segment)
            parentId = existing?.first ?: DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
                DocumentsContract.Document.MIME_TYPE_DIR, segment,
            )?.let(DocumentsContract::getDocumentId) ?: error("Cannot create folder $segment")
        }
        var finalName = segments.last()
        if (conflictCopy) finalName = originName(finalName, "Windows")
        if (conflictCopy) {
            var suffix = 1
            val base = finalName.substringBeforeLast('.', finalName)
            val extension = finalName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            while (findChild(tree, parentId, finalName) != null) finalName = "$base ($suffix++)$extension"
        }
        val existing = findChild(tree, parentId, finalName)?.second
        if (existing != null && !replaceExisting && !conflictCopy) error("Destination already exists")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val temporaryName = ".devicesync-$transferId.part"
        check(findChild(tree, parentId, temporaryName) == null) { "Partial destination already exists" }
        val temporary = DocumentsContract.createDocument(resolver, parent, mimeType.ifBlank { "application/octet-stream" }, temporaryName)
            ?: error("Cannot create partial destination")
        return FolderIncomingTarget(temporary.toString(), parent.toString(), finalName, existing?.toString())
    }

    fun commit(target: FolderIncomingTarget): String {
        val temporary = Uri.parse(target.temporaryUri)
        val existing = target.existingUri?.let(Uri::parse)
        var backup: Uri? = null
        try {
            if (existing != null) backup = DocumentsContract.renameDocument(resolver, existing, ".devicesync-backup-${UUID.randomUUID()}")
                ?: error("Cannot reserve the old destination")
            val committed = DocumentsContract.renameDocument(resolver, temporary, target.finalName)
                ?: error("Cannot commit the verified file")
            backup?.let { DocumentsContract.deleteDocument(resolver, it) }
            return committed.toString()
        } catch (error: Throwable) {
            backup?.let { runCatching { DocumentsContract.renameDocument(resolver, it, target.finalName) } }
            throw error
        }
    }

    private fun visit(tree: Uri, documentId: String, prefix: String, entries: MutableList<FolderManifestEntryPayload>) {
        queryChildren(tree, documentId).forEach { child ->
            val relative = FolderSyncPlanner.normalize(if (prefix.isEmpty()) child.name else "$prefix/${child.name}")
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) visit(tree, child.id, relative, entries)
            else {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                val hash = resolver.openInputStream(documentUri)?.use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) { val read = input.read(buffer); if (read < 0) break; if (read > 0) digest.update(buffer, 0, read) }
                    Base64Url.encode(digest.digest())
                } ?: return@forEach
                entries += FolderManifestEntryPayload(relative, child.size.coerceAtLeast(0),
                    Instant.ofEpochMilli(child.modified.coerceAtLeast(0)).toString(), hash)
            }
        }
    }

    private fun findChild(tree: Uri, parentId: String, name: String): Pair<String, Uri>? = queryChildren(tree, parentId)
        .firstOrNull { it.name == name }?.let { it.id to DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }

    private fun queryChildren(tree: Uri, parentId: String): List<DocumentRow> {
        val result = mutableListOf<DocumentRow>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += DocumentRow(cursor.getString(0), cursor.getString(1) ?: continue,
                cursor.getString(2), cursor.getLong(3), cursor.getLong(4))
        }
        return result
    }

    private data class DocumentRow(val id: String, val name: String, val mimeType: String, val modified: Long, val size: Long)
    private fun originName(name: String, origin: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name (from $origin)" else "${name.substring(0, dot)} (from $origin)${name.substring(dot)}"
    }
}

data class FolderIncomingTarget(val temporaryUri: String, val parentUri: String, val finalName: String, val existingUri: String?)

interface FolderIncomingFileAuthorizer {
    fun authorize(offer: FileOfferPayload): FolderIncomingTarget?
    fun commit(target: FolderIncomingTarget): String
}

class FolderSyncManager(
    context: Context,
    private val builder: SafFolderManifestBuilder,
    private val transport: SharingTransport,
    private val outgoing: PersistentOutgoingTransferQueue,
) : SharingMessageListener, FolderIncomingFileAuthorizer {
    private val preferences = context.getSharedPreferences("folder-sync", Context.MODE_PRIVATE)
    private val sessions = mutableMapOf<String, Session>()
    private var pendingRemote: FolderManifestPayload? = null
    private var rootUri: String? = preferences.getString("rootUri", null)
    private val _lastPlan = MutableStateFlow<FolderPlanPayload?>(null)
    val lastPlan: StateFlow<FolderPlanPayload?> = _lastPlan.asStateFlow()
    private val _status = MutableStateFlow("No folder sync active")
    val status: StateFlow<String> = _status.asStateFlow()

    init { transport.addSharingListener(this) }

    suspend fun start(treeUri: String): String {
        rootUri = treeUri
        preferences.edit().putString("rootUri", treeUri).apply()
        val pending = pendingRemote.also { pendingRemote = null }
        if (pending != null) { respond(pending); return pending.syncId }
        val syncId = UUID.randomUUID().toString()
        val manifest = builder.build(treeUri, syncId)
        sessions[syncId] = Session(syncId, treeUri, true, localManifest = manifest)
        send(ProtocolMessageType.FOLDER_MANIFEST, manifest)
        _status.value = "Manifest sent. Waiting for Windows."
        return syncId
    }

    suspend fun approve(syncId: String, resolutions: Map<String, String>) {
        val session = sessions[syncId] ?: error("Unknown folder sync")
        val plan = session.plan ?: error("Folder plan has not arrived")
        val conflicts = plan.operations.filter { it.action == "conflict" }.map { FolderSyncPlanner.normalize(it.relativePath) }.sorted()
        require(resolutions.keys.map(FolderSyncPlanner::normalize).sorted() == conflicts && conflicts.all { resolutions[it] in FolderConflictResolutions.all })
        val approval = FolderPlanApprovedPayload(syncId, conflicts.map { FolderConflictResolutionPayload(it, resolutions.getValue(it)) })
        session.localApproval = approval
        send(ProtocolMessageType.FOLDER_PLAN_APPROVED, approval)
        _status.value = "Local plan approved. Waiting for Windows approval."
        tryExecute(session)
    }

    override suspend fun onSharingMessage(message: ProtocolMessage) {
        runCatching { when (message.type) {
            ProtocolMessageType.FOLDER_MANIFEST.value -> handleManifest(ProtocolSerializer.decodePayload(message.payload))
            ProtocolMessageType.FOLDER_PLAN.value -> handlePlan(ProtocolSerializer.decodePayload(message.payload))
            ProtocolMessageType.FOLDER_PLAN_APPROVED.value -> {
                val approval = normalize(ProtocolSerializer.decodePayload(message.payload))
                val session = sessions[approval.syncId] ?: error("Unknown folder sync")
                session.remoteApproval = approval
                _status.value = "Windows approved the folder plan."
                tryExecute(session)
            }
        } }.onFailure { _status.value = "Folder sync failed: ${it.message}" }
    }

    override fun authorize(offer: FileOfferPayload): FolderIncomingTarget? {
        val syncId = offer.folderSyncId ?: return null
        val relative = offer.relativePath?.let(FolderSyncPlanner::normalize) ?: return null
        val session = sessions[syncId] ?: error("Folder transfer is not authorized")
        val expected = session.expectedIncoming[relative] ?: error("Folder transfer path is not authorized")
        check(!expected.consumed && expected.sha256 == offer.sha256 && expected.sizeBytes == offer.sizeBytes && expected.conflictCopy == offer.conflictCopy)
        expected.consumed = true
        return builder.createIncomingTarget(session.rootUri, relative, offer.transferId, offer.mimeType,
            expected.replaceExisting, offer.conflictCopy)
    }

    override fun commit(target: FolderIncomingTarget): String = builder.commit(target)

    private suspend fun handleManifest(remote: FolderManifestPayload) {
        val session = sessions[remote.syncId]
        if (session == null) {
            if (rootUri == null) { pendingRemote = remote; _status.value = "Windows requested sync. Select the matching Android folder." }
            else respond(remote)
            return
        }
        if (!session.initiatedLocally) return
        session.remoteManifest = remote
        val plan = FolderSyncPlanner.build(session.localManifest!!, remote)
        session.plan = plan
        _lastPlan.value = plan
        send(ProtocolMessageType.FOLDER_PLAN, plan)
    }

    private suspend fun respond(remote: FolderManifestPayload) {
        val root = rootUri ?: error("Select an Android folder")
        val local = builder.build(root, remote.syncId)
        sessions[remote.syncId] = Session(remote.syncId, root, false, local, remote)
        send(ProtocolMessageType.FOLDER_MANIFEST, local)
        _status.value = "Android manifest sent. Waiting for the plan."
    }

    private fun handlePlan(plan: FolderPlanPayload) {
        val session = sessions[plan.syncId] ?: error("Unknown folder sync")
        require(!session.initiatedLocally)
        plan.operations.forEach { FolderSyncPlanner.normalize(it.relativePath) }
        require(FolderSyncPlanner.build(session.remoteManifest!!, session.localManifest!!) == plan) {
            "The received plan does not match the two manifests"
        }
        session.plan = plan
        _lastPlan.value = plan
    }

    private suspend fun tryExecute(session: Session) {
        val plan = session.plan ?: return
        val local = session.localApproval?.let(::normalize) ?: return
        val remote = session.remoteApproval?.let(::normalize) ?: return
        require(local == remote) { "Windows and Android selected different conflict resolutions" }
        if (session.executionStarted) return
        session.executionStarted = true
        val resolutions = local.conflictResolutions.associate { it.relativePath to it.resolution }
        plan.operations.forEach { operation ->
            val relative = FolderSyncPlanner.normalize(operation.relativePath)
            val receive = when (operation.action) {
                "upload" -> !session.initiatedLocally
                "download" -> session.initiatedLocally
                "conflict" -> resolutions.getValue(relative) in setOf(FolderConflictResolutions.KEEP_WINDOWS, FolderConflictResolutions.KEEP_BOTH)
                else -> error("Unknown folder action")
            }
            if (receive) {
                val remoteEntry = session.remoteManifest!!.entries.single { FolderSyncPlanner.normalize(it.relativePath) == relative }
                val copy = operation.action == "conflict" && resolutions[relative] == FolderConflictResolutions.KEEP_BOTH
                session.expectedIncoming[relative] = ExpectedIncoming(remoteEntry.sha256, remoteEntry.sizeBytes, copy, operation.action == "conflict" && !copy)
            }
        }
        plan.operations.forEach { operation ->
            val relative = FolderSyncPlanner.normalize(operation.relativePath)
            val sendFile = when (operation.action) {
                "upload" -> session.initiatedLocally
                "download" -> !session.initiatedLocally
                "conflict" -> resolutions.getValue(relative) in setOf(FolderConflictResolutions.KEEP_ANDROID, FolderConflictResolutions.KEEP_BOTH)
                else -> false
            }
            if (sendFile) {
                val uri = builder.findDocument(session.rootUri, relative) ?: error("Source file is missing: $relative")
                val copy = operation.action == "conflict" && resolutions[relative] == FolderConflictResolutions.KEEP_BOTH
                outgoing.enqueueFolder(uri.toString(), AndroidFolderTransferMetadata(session.syncId, relative, copy))
            }
        }
        _status.value = "Both devices approved. Folder files were queued for verified transfer."
    }

    private fun normalize(value: FolderPlanApprovedPayload) = value.copy(conflictResolutions = value.conflictResolutions
        .map {
            require(it.resolution in FolderConflictResolutions.all) { "Unknown conflict resolution" }
            it.copy(relativePath = FolderSyncPlanner.normalize(it.relativePath))
        }.sortedBy { it.relativePath })

    private suspend fun send(type: ProtocolMessageType, payload: Any) {
        val json = when (payload) {
            is FolderManifestPayload -> ProtocolSerializer.payloadToJson(payload)
            is FolderPlanPayload -> ProtocolSerializer.payloadToJson(payload)
            is FolderPlanApprovedPayload -> ProtocolSerializer.payloadToJson(payload)
            else -> error("Unsupported folder payload")
        }
        transport.sendSharingMessage(type.value, json)
    }

    private data class Session(
        val syncId: String, val rootUri: String, val initiatedLocally: Boolean,
        var localManifest: FolderManifestPayload? = null, var remoteManifest: FolderManifestPayload? = null,
        var plan: FolderPlanPayload? = null, var localApproval: FolderPlanApprovedPayload? = null,
        var remoteApproval: FolderPlanApprovedPayload? = null, var executionStarted: Boolean = false,
        val expectedIncoming: MutableMap<String, ExpectedIncoming> = mutableMapOf(),
    )
    private data class ExpectedIncoming(val sha256: String, val sizeBytes: Long, val conflictCopy: Boolean,
        val replaceExisting: Boolean, var consumed: Boolean = false)
}
