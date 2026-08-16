package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationUiSourceContractTest {
    @Test
    fun `onboarding primary action reuses the documentation press spring geometry`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        assertTrue(source.contains("MutableInteractionSource()"))
        assertTrue(source.contains("collectIsPressedAsState()"))
        assertTrue(source.contains("motionPolicy.allowSpatialTransitions"))
        assertTrue(source.contains("stiffness = 400f"))
        assertTrue(source.contains("dampingRatio = 0.25f"))
        assertTrue(source.contains("targetValue = if (pressed) 12.dp else 32.dp"))
        assertTrue(source.contains("targetValue = if (pressed) 56.dp else 48.dp"))
        assertTrue(source.contains("targetValue = if (pressed) 1.1f else 1f"))
        assertTrue(source.contains(".height(56.dp)"))
        assertTrue(source.contains(".scale(contentScale)"))
    }

    @Test
    fun `generation settings description names only localized LLM parameters`() {
        val expected = linkedMapOf(
            "values" to "LLM parameters",
            "values-ar" to "معاملات LLM",
            "values-de" to "LLM-Parameter",
            "values-es" to "Parámetros del LLM",
            "values-fr" to "Paramètres du LLM",
            "values-ja" to "LLM パラメーター",
            "values-ko" to "LLM 매개변수",
            "values-pt-rBR" to "Parâmetros do LLM",
            "values-ru" to "Параметры LLM",
            "values-vi" to "Tham số LLM",
            "values-zh" to "LLM 参数",
            "values-zh-rTW" to "LLM 參數",
        )

        expected.forEach { (directory, value) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertEquals(
                "$directory settings_generation_desc",
                value,
                stringValue(strings, "settings_generation_desc"),
            )
        }
    }

    @Test
    fun `chat bottom dropdowns match the user message twenty four dp icon size`() {
        val attachment = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/AttachmentAddMenu.kt",
        )
        val bottomBar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val components = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val userMessage = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )

        assertTrue(components.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24"))
        assertTrue(attachment.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(bottomBar.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(components.contains("Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp)"))
        assertTrue(attachment.contains("Icons.Default.Add"))
        assertTrue(attachment.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(bottomBar.contains("Icons.Default.MoreVert"))
        assertTrue(bottomBar.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(userMessage.contains("leadingIcon = { Icon(Icons.Default.ContentCopy, null) }"))
    }

    @Test
    fun `normal chat bottom gradient completes above the bar`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")

        assertTrue(source.contains("val normalGradientTopPaddingPx = with(density) { 0.dp.toPx() }"))
        assertTrue(source.contains("val expandedGradientTopPaddingPx = with(density) { 20.dp.toPx() }"))
        assertTrue(source.contains("val gradientWidthPx = with(density) { 40.dp.toPx() }"))
        assertTrue(source.contains("expandedHeightPx = with(density) { 44.dp.toPx() }"))
        assertTrue(source.contains("val h = expandedGradientTopPaddingPx.coerceAtMost"))
        assertTrue(source.contains("val te = (normalGradientTopPaddingPx / totalH)"))
        assertTrue(source.contains("normalGradientTopPaddingPx + gradientWidthPx"))
    }

    @Test
    fun `Context and Thinking segment labels are localized in every supported locale`() {
        val keys = listOf(
            "context_title",
            "context_desc",
            "thinking_segment_display_mode",
            "thinking_segment_display_mode_desc",
            "thinking_segment_display_card",
            "thinking_segment_display_bottom_sheet",
            "thinking_segments_title",
        )
        val expected = linkedMapOf(
            "values-ar" to listOf(
                "السياق", "إدارة السياق", "مقاطع التفكير",
                "اختر مكان فتح مقاطع التفكير", "بطاقة", "لوحة سفلية", "مقاطع التفكير",
            ),
            "values-de" to listOf(
                "Kontext", "Kontextverwaltung", "Denksegmente",
                "Auswählen, wo Denksegmente geöffnet werden", "Karte",
                "Unteres Dialogfeld", "Denksegmente",
            ),
            "values-es" to listOf(
                "Contexto", "Gestión del contexto", "Segmentos de razonamiento",
                "Elige dónde se abren los segmentos de razonamiento", "Tarjeta",
                "Hoja inferior", "Segmentos de razonamiento",
            ),
            "values-fr" to listOf(
                "Contexte", "Gestion du contexte", "Segments de réflexion",
                "Choisissez où ouvrir les segments de réflexion", "Carte",
                "Panneau inférieur", "Segments de réflexion",
            ),
            "values-ja" to listOf(
                "コンテキスト", "コンテキスト管理", "思考セグメント",
                "思考セグメントを開く場所を選択", "カード", "ボトムシート", "思考セグメント",
            ),
            "values-ko" to listOf(
                "컨텍스트", "컨텍스트 관리", "사고 세그먼트",
                "사고 세그먼트를 열 위치 선택", "카드", "하단 시트", "사고 세그먼트",
            ),
            "values-pt-rBR" to listOf(
                "Contexto", "Gerenciamento de contexto", "Segmentos de raciocínio",
                "Escolha onde abrir os segmentos de raciocínio", "Cartão",
                "Painel inferior", "Segmentos de raciocínio",
            ),
            "values-ru" to listOf(
                "Контекст", "Управление контекстом", "Сегменты рассуждений",
                "Выберите, где открывать сегменты рассуждений", "Карточка",
                "Нижняя панель", "Сегменты рассуждений",
            ),
            "values-vi" to listOf(
                "Ngữ cảnh", "Quản lý ngữ cảnh", "Phân đoạn suy luận",
                "Chọn nơi mở các phân đoạn suy luận", "Thẻ",
                "Bảng dưới", "Phân đoạn suy luận",
            ),
            "values-zh" to listOf(
                "上下文", "上下文管理", "思考片段",
                "选择思考片段的打开位置", "卡片", "底部面板", "思考片段",
            ),
            "values-zh-rTW" to listOf(
                "上下文", "上下文管理", "思考片段",
                "選擇思考片段的開啟位置", "卡片", "底部面板", "思考片段",
            ),
        )

        expected.forEach { (directory, values) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            keys.zip(values).forEach { (key, value) ->
                assertEquals("$directory $key", value, stringValue(strings, key))
            }
        }
    }

    @Test
    fun `transcription chooser lists concrete models only while nullable summary stays compatible`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsTranscriptionPage.kt",
        )
        val chooser = source
            .substringAfter("if (showModelDialog)")
            .substringBefore("if (showAddDialog)")

        assertFalse(chooser.contains("transcription-model-none"))
        assertFalse(chooser.contains("setImageTranscriptionModel(null)"))
        assertTrue(source.contains("?: stringResource(R.string.transcription_no_model)"))
        assertTrue(source.contains("transcriptionModel == null"))
    }

    @Test
    fun `Appearance removes dead detailed token usage UI threading but keeps persistence compatibility`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val messageList = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )

        listOf(appearance, chatApp, messageList, messageItem, assistant).forEach {
            assertFalse(it.contains("detailedTokenUsage"))
        }
        assertFalse(appearance.contains("R.string.detailed_token_usage"))
        assertFalse(appearance.contains("setDetailedTokenUsage"))
        assertTrue(settings.contains("detailedTokenUsage"))
        assertTrue(settings.contains("saveDetailedTokenUsage"))
        assertTrue(archive.contains("\"detailedTokenUsage\""))
    }

    @Test
    fun `Thinking display policy is configurable only outside Timeline and auto expands Grouped cards`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val model = sourceFile("app/src/main/java/com/newoether/agora/model/ChatMessage.kt")

        assertTrue(model.contains("fun isAvailableFor(toolCallDisplayMode: String?)"))
        assertTrue(model.contains("fun effectiveMode("))
        assertTrue(model.contains("fun allowsAutoExpand("))
        assertTrue(model.contains(
            "ToolCallDisplayModes.normalize(toolCallDisplayMode) != ToolCallDisplayModes.TIMELINE"
        ))
        assertTrue(model.contains("ToolCallDisplayModes.GROUPED_TIMELINE"))
        assertTrue(model.contains("normalize(thinkingSegmentDisplayMode) == CARD"))
        assertTrue(appearance.contains(
            "ThinkingSegmentDisplayModes.isAvailableFor(normalizedToolCallDisplayMode)"
        ))
        assertTrue(appearance.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
        val toolBlocksIndex = appearance.indexOf("R.string.tool_call_display_mode")
        val thinkingSegmentIndex = appearance.indexOf("R.string.thinking_segment_display_mode")
        val autoExpandIndex = appearance.indexOf("R.string.auto_expand_active_group")
        assertTrue(toolBlocksIndex >= 0)
        assertTrue(thinkingSegmentIndex > toolBlocksIndex)
        assertTrue(autoExpandIndex > thinkingSegmentIndex)
        assertTrue(assistant.contains("ThinkingSegmentDisplayModes.effectiveMode("))
        assertTrue(messageItem.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
    }

    @Test
    fun `Settings destination rows omit redundant arrows without losing behavior`() {
        val home = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt",
        )
        val shell = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsShellPage.kt",
        )
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderPage.kt",
        )

        assertFalse(home.contains("KeyboardArrowRight"))
        assertTrue(home.contains(".clickable { selectedCategory = cat.key }"))
        assertTrue(home.contains("Column(modifier = Modifier.weight(1f))"))

        val sandbox = shell
            .substringAfter("private fun SandboxSection(")
            .substringBefore("private fun SandboxNotSupportedSection(")
        assertFalse(sandbox.contains("Icons.Default.ChevronRight"))
        assertTrue(sandbox.contains("Switch(checked = sandboxEnabled"))
        assertTrue(sandbox.contains("modifier = Modifier.clickable { onManage() }"))

        assertFalse(provider.contains("KeyboardArrowRight"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = name }"))
        assertTrue(provider.contains("config.protocol.displayName()"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = config.name }"))
        assertTrue(provider.contains(
            "modifier = Modifier.clickable { selectedProvider = Constants.PROVIDER_LOCAL }"
        ))
        assertFalse(provider.contains("Spacer(modifier = Modifier.width(4.dp))"))
    }

    @Test
    fun `every full screen viewer uses shared spatial entrance and exit with reduced motion fallback`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")

        assertTrue(source.contains(
            "private fun fullScreenPreviewEnterTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeIn(tween(durationMillis = 220))"))
        assertTrue(source.contains(
            "scaleIn(tween(durationMillis = 300, easing = FastOutSlowInEasing), initialScale = 0.96f)"
        ))
        assertTrue(source.contains("EnterTransition.None"))
        assertTrue(source.contains(
            "private fun fullScreenPreviewExitTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeOut(tween(durationMillis = 180))"))
        assertTrue(source.contains(
            "scaleOut(tween(durationMillis = 220, easing = FastOutLinearInEasing), targetScale = 0.96f)"
        ))
        assertTrue(source.contains("ExitTransition.None"))
        assertEquals(
            2,
            Regex("enter = fullScreenPreviewEnterTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex("exit = fullScreenPreviewExitTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(2, Regex("if \\(!currentState && !isRunning\\)").findAll(source).count())
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)"
        ))
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.TEXT_PREVIEW)"
        ))
        assertTrue(source.contains("val urls = lastUrls ?: return@AnimatedVisibility"))
        assertTrue(source.contains("if (savedContent != null && savedName != null)"))
    }

    private fun stringValue(xml: String, key: String): String {
        val regex = Regex("""<string name="$key">([^<]*)</string>""")
        return requireNotNull(regex.find(xml)) { "Missing $key" }.groupValues[1]
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
