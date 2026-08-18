package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSearchLoadingSourceContractTest {
    @Test
    fun `drawer list uses a bounded projection with truthful fade-backed loading and search states`() {
        val dao = source("data/local/ChatDao.kt")
        val repository = source("data/repository/ConversationRepository.kt")
        val viewModel = source("viewmodel/ChatViewModel.kt")
        val drawer = source("ui/chat/ChatDrawerContent.kt")
        val searchState = source("ui/chat/search/DrawerSearchState.kt")
        val searchBar = source("ui/chat/search/DrawerSearchBar.kt")

        assertTrue(dao.contains("SELECT id, title, systemPromptId, modelId, taskId, origin, graduated, hasUnreadGeneration FROM conversations"))
        assertTrue(dao.contains("fun getAllConversations(): Flow<List<ChatConversation>>"))
        assertFalse(dao.contains("SELECT * FROM conversations WHERE taskId IS NULL ORDER BY lastUpdated DESC"))
        assertTrue(repository.contains("fun getAllConversations(): Flow<List<ChatConversation>> = chatDao.getAllConversations()"))
        assertTrue(viewModel.contains("StateFlow<List<ChatConversation>?>"))
        assertTrue(viewModel.contains("stateIn(viewModelScope, SharingStarted.Eagerly, null)"))
        assertTrue(drawer.contains("val isConversationListLoading = conversationList == null"))
        assertTrue(drawer.contains("visible = isConversationListLoading"))
        assertTrue(drawer.contains("enter = fadeIn(tween(180))"))
        assertTrue(drawer.contains("exit = fadeOut(tween(180))"))
        assertTrue(searchState.contains("var isSearching by mutableStateOf(false)"))
        assertTrue(searchState.contains("isSearching = true"))
        assertTrue(searchState.contains("} finally {\n            isSearching = false"))
        assertTrue(searchBar.contains("searching: Boolean = false"))
        assertTrue(searchBar.contains("visible = searching"))
        assertTrue(searchBar.contains("CircularProgressIndicator("))
    }

    private fun source(relative: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relative").readText()

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
