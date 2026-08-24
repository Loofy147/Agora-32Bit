package com.newoether.agora.ui.chat.bottombar

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.util.DebugLog
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.util.FileValidator
import com.newoether.agora.util.PdfPageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class CameraCaptureTarget(
    val uri: Uri,
    val privatePath: String,
)

data class PendingAttachmentRemoval(
    val id: Long,
    val ownerConversationId: String,
    val attachment: SelectedAttachment,
)

/**
 * State holder for the chat composer's attachment subsystem (images / videos / PDFs /
 * generic files): the picked-attachment list, per-attachment processing progress, and
 * the PDF page-select + video-slice dialog state, plus the logic for picking, frame
 * extraction, page rendering, and removal.
 *
 * Hoisted out of the `ChatBottomBar` composable body (Phase E6) so the composable holds
 * UI and this holder owns attachment state/behaviour — the Compose "separate state from
 * UI" best practice. Obtain via [rememberChatComposerState]; the composable reads/writes
 * `composer.xxx` and wires the launchers/dialogs to these methods.
 */
class ChatComposerState(
    private val context: Context,
    private val haptics: AgoraHaptics,
    private val scope: CoroutineScope,
) {
    var selectedAttachments by mutableStateOf<List<SelectedAttachment>>(emptyList())
    var processingStates by mutableStateOf<Map<String, Float>>(emptyMap())
    var pendingSend by mutableStateOf(false)
    private var draftOwnerConversationId: String? = null
    private var attachmentRemovalIds = 0L
    var pendingAttachmentRemovals by mutableStateOf<List<PendingAttachmentRemoval>>(emptyList())
        private set

    // PDF page selection dialog state
    var showPdfPageDialog by mutableStateOf(false)
    var pendingPdfUri by mutableStateOf<String?>(null)
    var pendingPdfPages by mutableIntStateOf(0)
    var pendingPdfFileName by mutableStateOf<String?>(null)
    var pendingPdfMimeType by mutableStateOf<String?>(null)
    var pendingPdfRenderedPaths by mutableStateOf<List<String>>(emptyList())
    var pendingPdfIsRendering by mutableStateOf(false)
    var pendingPdfRenderProgress by mutableStateOf(0 to 0)
    var pdfDialogHiddenForPreview by mutableStateOf(false)
    // Background render job for the page-select dialog, so a dismiss can cancel it and
    // let renderAllPages clean up its partially-written page files.
    var pdfRenderJob by mutableStateOf<Job?>(null)
    // In-flight video frame-extraction jobs, keyed by video uri, so removing a video while
    // it is still extracting can cancel the job (which deletes its partial frame files).
    val videoExtractionJobs = mutableMapOf<String, Job>()

    // Video slicing dialog state
    var showVideoSliceDialog by mutableStateOf(false)
    var pendingVideoUri by mutableStateOf<String?>(null)
    var pendingVideoDurationMs by mutableLongStateOf(0L)
    var pendingVideoQueue by mutableStateOf<List<String>>(emptyList())
    private var videoMetadataJob: Job? = null
    private val attachmentInspectionMutex = Mutex()

    // Generic file validation and camera failures share one dialog surface, but not one title.
    // Keeping the title alongside the message prevents camera launch errors from being
    // misreported as an unsupported MIME type.
    private var rejectionMessageState by mutableStateOf<String?>(null)
    private var rejectionTitleState by mutableIntStateOf(
        com.newoether.agora.R.string.file_unsupported_title,
    )
    var rejectedMessage: String?
        get() = rejectionMessageState
        set(value) {
            rejectionMessageState = value
            rejectionTitleState = com.newoether.agora.R.string.file_unsupported_title
        }
    val rejectedTitleRes: Int
        get() = rejectionTitleState

    private data class InspectedFile(
        val uri: Uri,
        val validation: FileValidator.Result,
        val fileName: String?,
        val pageCount: Int,
    )

    /** Clear the attachment list after a successful send. The extracted-frame / rendered-page
     *  files are now owned by the stored message (via images field in MessageEntity) — they
     *  must NOT be deleted here; message deletion handles that. */
    fun clearAttachments() {
        selectedAttachments = emptyList()
    }

    fun bindDraftOwner(conversationId: String?) {
        draftOwnerConversationId = conversationId
    }

    fun isDraftOwner(conversationId: String): Boolean =
        draftOwnerConversationId == conversationId

    fun attachmentRemovalsFor(conversationId: String): List<PendingAttachmentRemoval> =
        pendingAttachmentRemovals.filter { removal ->
            removal.ownerConversationId == conversationId
        }

    fun acknowledgeAttachmentRemovals(ids: Set<Long>) {
        if (ids.isNotEmpty()) {
            pendingAttachmentRemovals =
                pendingAttachmentRemovals.filterNot { removal -> removal.id in ids }
        }
    }

    /**
     * Commits the current PDF selection without doing filesystem work in the click transaction.
     * Selected page files become message-owned attachments; unselected files are reclaimed on IO.
     */
    fun confirmPendingPdfSelection(selectedPages: Set<Int>) {
        val uri = pendingPdfUri ?: return
        val mimeType = pendingPdfMimeType
        val fileName = pendingPdfFileName
        val renderedPaths = pendingPdfRenderedPaths
        val keptPaths = renderedPaths.filterIndexed { index, _ -> index in selectedPages }
        val discardedPaths = renderedPaths.filterIndexed { index, _ -> index !in selectedPages }
        resetPendingPdfState()
        deleteFilesAsync(discardedPaths)
        processingStates = processingStates + (uri to 0f)
        scope.launch {
            val localPath = copyToPrivate(Uri.parse(uri), "pdf")
            if (localPath != null) {
                selectedAttachments = selectedAttachments + SelectedAttachment(
                    uri = uri,
                    type = "pdf",
                    mimeType = mimeType,
                    fileName = fileName,
                    selectedPages = keptPaths.indices.toSet(),
                    preRenderedPaths = keptPaths,
                    localPath = localPath,
                )
            } else {
                deleteFilesAsync(keptPaths)
                rejectedMessage = context.getString(
                    com.newoether.agora.R.string.attachment_copy_failed_file,
                )
            }
            processingStates = processingStates - uri
        }
    }

    /**
     * Cancels and clears the pending PDF state synchronously, then reclaims completed page files
     * off Main. A cancelled renderer owns cleanup of any files it had not published yet.
     */
    fun dismissPendingPdf() {
        pdfRenderJob?.cancel()
        pdfRenderJob = null
        val discardedPaths = pendingPdfRenderedPaths
        resetPendingPdfState()
        deleteFilesAsync(discardedPaths)
    }

    private fun resetPendingPdfState() {
        showPdfPageDialog = false
        pendingPdfUri = null
        pendingPdfPages = 0
        pendingPdfFileName = null
        pendingPdfMimeType = null
        pendingPdfRenderedPaths = emptyList()
        pendingPdfIsRendering = false
        pendingPdfRenderProgress = 0 to 0
        pdfDialogHiddenForPreview = false
    }

    private fun deleteFilesAsync(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            paths.forEach { path -> runCatching { java.io.File(path).delete() } }
        }
    }

    /** Copy a content URI to app-private storage, returning the absolute path (or null). */
    private suspend fun copyToPrivate(uri: Uri, ext: String): String? {
        return withContext(Dispatchers.IO) {
            val target = java.io.File(context.filesDir, "att_${UUID.randomUUID()}.$ext")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                input.use {
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.absolutePath
            } catch (_: Exception) {
                runCatching { target.delete() }
                null
            }
        }
    }

    /**
     * Creates the camera's output file inside Agora's private files directory and exposes only
     * this one path through FileProvider. The system camera writes the full-resolution image
     * directly; Agora never needs CAMERA permission or a public gallery entry.
     */
    suspend fun createCameraCaptureTarget(): CameraCaptureTarget? =
        withContext(Dispatchers.IO) {
            var target: java.io.File? = null
            try {
                val directory = java.io.File(context.filesDir, "images")
                check(directory.exists() || directory.mkdirs()) {
                    "Unable to create private image directory"
                }
                target = java.io.File(directory, "camera_${UUID.randomUUID()}.jpg")
                check(target.createNewFile()) { "Unable to create camera target" }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
                CameraCaptureTarget(uri = uri, privatePath = target.absolutePath)
            } catch (error: Exception) {
                target?.let { runCatching { it.delete() } }
                DebugLog.e("ChatComposer", "Unable to prepare camera capture", error)
                null
            }
        }

    /**
     * Commits a successful camera file as a normal image attachment. Cancellation and malformed
     * zero-byte camera results reclaim the private target asynchronously.
     */
    fun completeCameraCapture(privatePath: String, captured: Boolean) {
        scope.launch {
            val attachment = withContext(Dispatchers.IO) {
                val file = privateCameraFile(privatePath)
                if (file == null) {
                    null
                } else if (!captured || !file.isFile || file.length() <= 0L) {
                    runCatching { file.delete() }
                    null
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        SelectedAttachment(
                            uri = uri.toString(),
                            type = "image",
                            fileName = file.name,
                            mimeType = "image/jpeg",
                            fileSize = file.length(),
                            localPath = file.absolutePath,
                        )
                    }.getOrElse { error ->
                        runCatching { file.delete() }
                        DebugLog.e("ChatComposer", "Unable to attach camera capture", error)
                        null
                    }
                }
            }
            if (attachment != null) {
                haptics.selection()
                selectedAttachments = selectedAttachments + attachment
            } else if (captured) {
                rejectedMessage = context.getString(
                    com.newoether.agora.R.string.attachment_copy_failed_image,
                )
            }
        }
    }

    private fun privateCameraFile(path: String): java.io.File? = runCatching {
        val directory = java.io.File(context.filesDir, "images").canonicalFile
        java.io.File(path).canonicalFile.takeIf { file ->
            file.parentFile == directory && file.name.startsWith("camera_")
        }
    }.getOrNull()

    fun reportCameraPreparationFailure() {
        rejectionTitleState = com.newoether.agora.R.string.camera
        rejectionMessageState = context.getString(
            com.newoether.agora.R.string.attachment_copy_failed_image,
        )
    }

    /** Remove the attachment at [index]. Conversation-owned files are reclaimed only after the
     *  new draft is durable; new-chat files have no possible draft owner and can be deleted now. */
    fun removeAttachmentAt(index: Int) {
        val removed = selectedAttachments.getOrNull(index) ?: return
        haptics.selection()
        // Cancel in-flight video extraction + delete partial frames
        if (videoExtractionJobs.containsKey(removed.uri)) {
            videoExtractionJobs[removed.uri]?.cancel()
            videoExtractionJobs.remove(removed.uri)
        }
        val uriStr = removed.uri
        selectedAttachments = selectedAttachments.toMutableList().also { it.removeAt(index) }
        processingStates = processingStates - uriStr
        val ownerConversationId = draftOwnerConversationId
        if (ownerConversationId == null) {
            // A new-chat attachment has never entered a persisted draft. It is still unique to
            // this composer, so reclaim it off Main immediately.
            scope.launch(Dispatchers.IO) {
                com.newoether.agora.util.AttachmentFiles.deleteBacking(removed)
            }
        } else {
            attachmentRemovalIds =
                if (attachmentRemovalIds == Long.MAX_VALUE) 1L else attachmentRemovalIds + 1L
            pendingAttachmentRemovals = pendingAttachmentRemovals + PendingAttachmentRemoval(
                id = attachmentRemovalIds,
                ownerConversationId = ownerConversationId,
                attachment = removed,
            )
        }
    }

    // Helper: process next video in queue, showing slice dialog
    fun processNextVideo() {
        if (
            pendingVideoQueue.isEmpty() ||
            showVideoSliceDialog ||
            videoMetadataJob?.isActive == true
        ) return

        val uri = pendingVideoQueue.first()
        pendingVideoQueue = pendingVideoQueue.drop(1)
        videoMetadataJob = scope.launch {
            try {
                val durationMs = withContext(Dispatchers.IO) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, Uri.parse(uri))
                            retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    } catch (_: Exception) {
                        0L
                    }
                }
                pendingVideoUri = uri
                pendingVideoDurationMs = durationMs
                showVideoSliceDialog = true
            } finally {
                videoMetadataJob = null
            }
        }
    }

    // Start frame extraction for a video, return list of frame paths
    suspend fun extractVideoFrames(
        videoUri: String,
        frameCount: Int,
        intervalMs: Long,
        progressKey: String = videoUri,
    ): List<String> {
        return withContext(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                retriever.setDataSource(context, android.net.Uri.parse(videoUri))
                var timeUs = 0L
                val intervalUs = intervalMs * 1000L
                for (i in 0 until frameCount) {
                    ensureActive()
                    val bitmap = retriever.getFrameAtTime(
                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        val file = java.io.File(context.filesDir, "vid_${java.util.UUID.randomUUID()}_$i.jpg")
                        file.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        bitmap.recycle()
                        paths.add(file.absolutePath)
                    }
                    timeUs += intervalUs
                    // Snapshot-map read-modify-write must stay main-confined (see onPickImages).
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        processingStates = processingStates + (progressKey to (i + 1).toFloat() / frameCount)
                    }
                }
                } finally { retriever.release() }
            } catch (c: CancellationException) {
                // Removed mid-extraction: drop the partial frame files instead of orphaning them.
                paths.forEach { runCatching { java.io.File(it).delete() } }
                throw c
            } catch (e: Exception) { DebugLog.e("ChatComposer", "Video frame extraction failed", e) }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                processingStates = processingStates - progressKey
            }
            paths
        }
    }

    /** Handle images picked from the photo picker or clipboard. Copies each URI to app-private
     *  storage before publishing the attachment so no draft can retain an expiring permission. */
    fun onPickImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        haptics.selection()
        val processingKeys = uris.map { it.toString() }.toSet()
        processingStates = processingStates + processingKeys.associateWith { 0f }
        scope.launch {
            val copiedAttachments = mutableListOf<SelectedAttachment>()
            var copyFailed = false
            for (uriObj in uris) {
                val mimeType = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.getType(uriObj)
                    } catch (_: Exception) {
                        null
                    }
                }
                val localPath = copyToPrivate(uriObj, "img")
                if (localPath != null) {
                    copiedAttachments += SelectedAttachment(
                        uri = Uri.fromFile(java.io.File(localPath)).toString(),
                        type = "image",
                        mimeType = mimeType,
                        localPath = localPath,
                    )
                } else {
                    copyFailed = true
                }
            }
            if (copiedAttachments.isNotEmpty()) {
                selectedAttachments = selectedAttachments + copiedAttachments
            }
            if (copyFailed) {
                rejectedMessage = context.getString(
                    com.newoether.agora.R.string.attachment_copy_failed_image,
                )
            }
            processingStates = processingStates - processingKeys
        }
    }

    /** Handle videos picked from the video picker; queues them and kicks off the slice dialog. */
    fun onPickVideos(uris: List<Uri>) {
        if (uris.isNotEmpty()) haptics.selection()
        val urisToQueue = uris.map { it.toString() }
        pendingVideoQueue = pendingVideoQueue + urisToQueue
        if (!showVideoSliceDialog) processNextVideo()
    }

    /** Handle generic files picked from the document picker (validates, queues first PDF for
     *  page rendering, adds the rest as attachments). */
    fun onPickFiles(uris: List<Uri>, onInitPdfSelection: ((Set<Int>) -> Unit)?) {
        if (uris.isEmpty()) return
        scope.launch {
            // SAF providers can block on MIME, metadata and page-count queries. Serialize commits
            // on Main, but perform the complete inspection batch on IO.
            attachmentInspectionMutex.withLock {
                val inspected = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val validation = FileValidator.validate(context, uri)
                        val fileName = if (validation.valid) {
                            FileValidator.resolveFileName(context, uri)
                        } else {
                            null
                        }
                        val pageCount = if (
                            validation.valid &&
                            validation.mimeType == "application/pdf"
                        ) {
                            PdfPageRenderer.getPageCount(context, uri)
                        } else {
                            0
                        }
                        InspectedFile(uri, validation, fileName, pageCount)
                    }
                }

                val attachmentsToCopy = mutableListOf<Pair<Uri, SelectedAttachment>>()
                val rejectedMessages = mutableListOf<String>()
                for (item in inspected) {
                    val validation = item.validation
                    if (!validation.valid) {
                        rejectedMessages.add(
                            FileValidator.errorMessage(
                                context,
                                checkNotNull(validation.error),
                                validation.mimeType,
                            )
                        )
                        continue
                    }
                    val mimeType = validation.mimeType
                    val type = if (mimeType == "application/pdf") "pdf" else "file"
                    if (type == "pdf" && !showPdfPageDialog && item.pageCount > 0) {
                        pendingPdfUri = item.uri.toString()
                        pendingPdfPages = item.pageCount
                        pendingPdfFileName = item.fileName
                        pendingPdfMimeType = mimeType
                        pendingPdfRenderedPaths = emptyList()
                        pendingPdfIsRendering = true
                        pendingPdfRenderProgress = 0 to item.pageCount
                        showPdfPageDialog = true
                        onInitPdfSelection?.invoke(
                            (0 until minOf(item.pageCount, 5)).toSet()
                        )
                        pdfRenderJob = scope.launch {
                            val paths = withContext(Dispatchers.IO) {
                                PdfPageRenderer.renderAllPages(
                                    context,
                                    item.uri,
                                    maxPages = item.pageCount,
                                    onProgress = { cur, total ->
                                        scope.launch {
                                            pendingPdfRenderProgress = cur to total
                                        }
                                    },
                                )
                            }
                            pendingPdfRenderedPaths = paths
                            pendingPdfIsRendering = false
                        }
                        continue
                    }
                    val attachment = SelectedAttachment(
                        uri = item.uri.toString(),
                        type = type,
                        mimeType = mimeType,
                        fileName = item.fileName,
                    )
                    attachmentsToCopy.add(item.uri to attachment)
                }
                if (rejectedMessages.isNotEmpty()) {
                    haptics.reject()
                    rejectedMessage = rejectedMessages.joinToString("\n")
                }
                if (attachmentsToCopy.isNotEmpty()) haptics.selection()

                // Every external source becomes composer-visible only after its private copy exists.
                val copiedAttachments = mutableListOf<SelectedAttachment>()
                for ((uri, attachment) in attachmentsToCopy) {
                    val uriStr = uri.toString()
                    val ext = if (attachment.type == "pdf") {
                        "pdf"
                    } else {
                        attachment.fileName?.substringAfterLast('.', "bin") ?: "bin"
                    }
                    processingStates = processingStates + (uriStr to 0f)
                    val localPath = copyToPrivate(uri, ext)
                    if (localPath != null) {
                        copiedAttachments += attachment.copy(localPath = localPath)
                    } else {
                        rejectedMessage = context.getString(
                            com.newoether.agora.R.string.attachment_copy_failed_file,
                        )
                    }
                    processingStates = processingStates - uriStr
                }
                if (copiedAttachments.isNotEmpty()) {
                    selectedAttachments = selectedAttachments + copiedAttachments
                }
            }
        }
    }

    /** Copy a confirmed video to private storage, publish it, then extract frames from the copy. */
    fun addSlicedVideo(vidUri: String, frameCount: Int, intervalMs: Long) {
        processingStates = processingStates + (vidUri to 0f)

        // Track the whole copy + extraction job so removing the published attachment cancels the
        // frame work and reclaims both the private original and partial frames through normal
        // attachment removal.
        val job = scope.launch {
            val sourceUri = Uri.parse(vidUri)
            val (fileName, mimeType) = withContext(Dispatchers.IO) {
                FileValidator.resolveFileName(context, sourceUri) to try {
                    context.contentResolver.getType(sourceUri)
                } catch (_: Exception) {
                    null
                }
            }
            val ext = fileName
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    mimeType?.contains("webm") == true -> "webm"
                    mimeType?.contains("quicktime") == true -> "mov"
                    else -> "mp4"
                }
            val localPath = copyToPrivate(sourceUri, ext)
            if (localPath == null) {
                rejectedMessage = context.getString(
                    com.newoether.agora.R.string.attachment_copy_failed_file,
                )
                processingStates = processingStates - vidUri
                videoExtractionJobs.remove(vidUri)
                return@launch
            }

            val attachment = SelectedAttachment(
                uri = vidUri,
                type = "video",
                frameCount = frameCount,
                sliceIntervalMs = intervalMs,
                fileName = fileName,
                mimeType = mimeType ?: "video/*",
                fileSize = java.io.File(localPath).length(),
                localPath = localPath,
            )
            selectedAttachments = selectedAttachments + attachment
            val framePaths = extractVideoFrames(
                videoUri = Uri.fromFile(java.io.File(localPath)).toString(),
                frameCount = frameCount,
                intervalMs = intervalMs,
                progressKey = vidUri,
            )
            selectedAttachments = selectedAttachments.map { current ->
                if (current.localId == attachment.localId) {
                    current.copy(processedFrames = framePaths)
                } else {
                    current
                }
            }
            videoExtractionJobs.remove(vidUri)
        }
        videoExtractionJobs[vidUri] = job
    }
}

@Composable
fun rememberChatComposerState(): ChatComposerState {
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    val scope = rememberCoroutineScope()
    return remember(context, haptics, scope) { ChatComposerState(context, haptics, scope) }
}
