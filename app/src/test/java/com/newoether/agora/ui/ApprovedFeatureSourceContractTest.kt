package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedFeatureSourceContractTest {
    @Test
    fun cacheCountsAreEagerCoalescedAndAggregated() {
        val root = sourceRoot()
        val rag = source(root, "com/newoether/agora/viewmodel/RagManager.kt")
        val dao = source(root, "com/newoether/agora/data/local/ChatDao.kt")
        val entities = source(root, "com/newoether/agora/data/local/ChatEntities.kt")
        val database = source(root, "com/newoether/agora/data/local/ChatDatabase.kt")

        assertTrue(rag.contains("init {\n        loadCacheCounts()"))
        assertTrue(rag.contains("cacheCountRefreshJob?.isActive == true"))
        assertTrue(rag.contains("getEmbeddingCountsByModels(modelIds)"))
        assertFalse(
            rag.substringAfter("private suspend fun refreshCacheCounts")
                .substringBefore("// ── Embedding-model CRUD")
                .contains("getEmbeddingCountByModel"),
        )
        assertTrue(dao.contains("GROUP BY e.modelId"))
        assertTrue(dao.contains("getEmbeddingCountsByModels"))
        assertTrue(entities.contains("Index(value = [\"modelId\"])"))
        assertTrue(database.contains("CURRENT_VERSION = 23"))
        assertTrue(database.contains("MIGRATION_22_23"))
    }

    @Test
    fun mediaViewerAndClipboardImagesUseTheApprovedBoundaries() {
        val root = sourceRoot()
        val main = source(root, "com/newoether/agora/MainActivity.kt")
        val dialog = source(
            root,
            "com/newoether/agora/ui/chat/FullScreenMediaPreviewDialog.kt",
        )
        val composer = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val imageActions = source(
            root,
            "com/newoether/agora/ui/chat/ImageActions.kt",
        )

        assertTrue(main.contains("FullScreenMediaPreviewDialog("))
        assertTrue(dialog.contains("Dialog("))
        assertTrue(dialog.contains(".background(Color.Black)"))
        assertTrue(dialog.contains("visibilityTransition.AnimatedVisibility("))
        assertTrue(dialog.contains("visibilityTransition.animateFloat("))
        assertTrue(dialog.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemDim()"))
        assertTrue(dialog.indexOf("FullScreenMediaViewer(") > dialog.indexOf(".background(Color.Black)"))
        assertTrue(composer.contains(".contentReceiver(clipboardImageReceiver)"))
        assertTrue(composer.contains("transferableContent.consume"))
        assertTrue(composer.contains("hasMediaType(MediaType.Image)"))
        assertTrue(composer.contains("composer.onPickImages(imageUris)"))
        assertTrue(composer.contains("return remaining"))
    }

    @Test
    fun streamingFadeUsesColorAlphaSpansAcrossMarkdownThinkingAndToolSummaries() {
        val root = sourceRoot()
        val fade = source(
            root,
            "com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdown.kt",
        )
        val assets = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt",
        )
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )
        val tool = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolResultContent.kt",
        )

        assertTrue(fade.contains("fun streamingTailAnnotatedString("))
        assertTrue(fade.contains("fun rememberStreamingGlyphFade("))
        assertFalse(fade.contains("fun Modifier.stableStreamingGlyphFade("))
        assertFalse(fade.contains("BlendMode.DstIn"))
        assertTrue(assets.contains("content = base,"))
        assertTrue(assets.contains("rememberStreamingGlyphFade("))
        assertFalse(assets.contains(".stableStreamingGlyphFade("))
        assertTrue(timeline.contains("StableStreamingText("))
        assertTrue(tool.contains("StableStreamingText("))
        // Document-level birth-time tracking: state survives node restructures, block promotion,
        // and subtree re-keying; per-token arrival history survives pipeline conflation.
        assertTrue(fade.contains("fadeSample: StreamingTailFadeSample?"))
        assertTrue(fade.contains("fun computeBlockFadeSpecs("))
        assertTrue(fade.contains("internal fun StreamingGlyphFadeSpec?.nodeFade("))
        assertTrue(fade.contains("fun distributeArrivalBirths("))
        assertFalse(fade.contains("lastVisibleSourceOffset"))
        assertTrue(assets.contains("fade = nodeFade,"))
        assertFalse(assets.contains("enabled = fadeThisNode"))
    }

    @Test
    fun toolResultImageContextRowKeepsANonProtocolIdPrefix() {
        val root = sourceRoot()
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")

        // The API-only image-context row must never start with a protocol prefix: provider
        // serializers branch on tool_/result_ and would silently drop the row (view_image
        // results would display in the UI but never reach the model).
        assertTrue(toolMessages.contains("id = \"image_context_\$digest\""))
        assertFalse(toolMessages.contains("tool_image_context_"))
    }

    @Test
    fun toolResultImageTranscriptionFollowsTheGenericDeclaredRule() {
        val root = sourceRoot()
        val toolProvider = source(root, "com/newoether/agora/tool/ToolProvider.kt")
        val shell = source(root, "com/newoether/agora/tool/ShellToolProvider.kt")
        val executor = source(
            root,
            "com/newoether/agora/viewmodel/GenerationToolBatchEffectExecutor.kt",
        )
        val manager = source(root, "com/newoether/agora/viewmodel/GenerationManager.kt")
        val transcription = source(root, "com/newoether/agora/viewmodel/TranscriptionManager.kt")
        val contracts = source(root, "com/newoether/agora/viewmodel/GenerationContracts.kt")

        // The tool declares intent via the result flag; the executor implements one generic
        // rule with no tool-name routing; the transcriber travels the per-generation call
        // chain; GenerationContext stays free of function fields.
        assertTrue(toolProvider.contains("val transcribeImages: Boolean = false"))
        assertTrue(shell.contains("transcribeImages = true"))
        assertTrue(executor.contains("result.transcribeImages && toolImage != null && transcriber != null"))
        assertFalse(executor.contains("\"view_image\""))
        assertFalse(executor.contains("[Image description]"))
        assertTrue(executor.contains("appendTranscriptionSegment("))
        assertTrue(executor.contains("toolImageTranscriber = request.toolImageTranscriber"))
        assertTrue(manager.contains("toolImageTranscriber ="))
        assertTrue(manager.contains("transcriptionManager.describeImageWithProgress("))
        assertTrue(transcription.contains("suspend fun describeImageWithProgress("))
        assertFalse(contracts.contains("toolImageTranscriber"))
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")
        assertTrue(toolMessages.contains("--- Image Transcription: view_image ---"))
        assertTrue(toolMessages.contains("transcriptionDescriptionsForBatch("))
        // Defect pins (owner device reports): transcription-enabled models never receive raw
        // images; the compact group title stays the transcription label while TOOL_CALLING;
        // the thinking block always announces the transcribing state.
        val pathBuilder = source(root, "com/newoether/agora/viewmodel/GenerationApiPathBuilder.kt")
        val titles = source(
            root,
            "com/newoether/agora/ui/chat/message/ThinkingSegmentPresentation.kt",
        )
        assertTrue(pathBuilder.contains("includeImages = !request.context.imageTranscriptionEnabled"))
        assertTrue(titles.contains("segs.any { it.type == \"transcription\" }"))
        assertTrue(transcription.contains("onProgress(context.getString(R.string.transcription_ellipsis_single))"))
    }

    @Test
    fun backgroundShellJobDoesNotOccupyTheGroupLoadingIndicator() {
        val root = sourceRoot()
        val presentation = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolPresentation.kt",
        )
        val labels = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemToolLabels.kt",
        )

        // isActive drives the group loading bar; a detached background job must not occupy it.
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||\n            state == ToolPresentationState.RUNNING"
        ))
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING\n"
        ))
        // The card still shows the background status (matched before isActive).
        assertTrue(labels.contains(
            "presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->"
        ))
    }

    @Test
    fun ratingDialogContentKeepsStandardMargins() {
        val root = sourceRoot()
        val rating = source(root, "com/newoether/agora/ui/settings/RatingForm.kt")

        // The dialog content must never touch the 28 dp rounded surface edge.
        assertTrue(rating.contains(
            "Modifier\n            .clearFocusOnTap()\n            .padding(horizontal = 24.dp, vertical = 20.dp)"
        ))
    }

    @Test
    fun skillsAreSavedOnlyFrozenCatalogToolsWithNoActiveSkill() {
        val root = sourceRoot()
        val manager = source(root, "com/newoether/agora/data/SkillManager.kt")
        val provider = source(root, "com/newoether/agora/tool/SkillToolProvider.kt")
        val builder = source(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        )
        val exporter = source(root, "com/newoether/agora/data/DataExporter.kt")
        val importer = source(root, "com/newoether/agora/data/DataImporter.kt")
        val settings = source(
            root,
            "com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )

        assertTrue(manager.contains("File(context.filesDir, \"skill_db\")"))
        assertTrue(manager.contains("fun catalog(): String"))
        assertFalse(manager.contains("active_skill"))
        assertTrue(provider.contains("list_skill_files"))
        assertTrue(provider.contains("read_skill_file"))
        assertTrue(provider.contains("create_skill_file"))
        assertTrue(provider.contains("edit_skill_file"))
        assertTrue(provider.contains("delete_skill_file"))
        assertFalse(provider.contains("update_active_skill"))
        assertTrue(builder.contains("skillCatalog = if (skillReadAccess) skillManager.catalog()"))
        assertTrue(builder.contains("effectiveSystemPromptWithSkills"))
        assertTrue(exporter.contains("memories/skill_db/"))
        assertTrue(importer.contains("memories/skill_db/"))
        assertTrue(settings.contains("settings.accessSkills.collectAsState()"))
        assertFalse(settings.contains("Active Skill"))
    }

    private fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    private fun sourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate source root")
    }
}
