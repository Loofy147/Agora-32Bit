package com.newoether.agora.tool

import com.newoether.agora.data.SkillManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolProviderTest {
    private val skillManager = mockk<SkillManager> {
        every { listFiles() } returns listOf(
            SkillManager.SkillFileInfo("review.md", "Review changes"),
        )
        every { readFile(any()) } returns "skill body"
        every { createFile(any(), any(), any()) } returns "Created"
        every { editFile(any(), any(), any(), any(), any(), any()) } returns "Updated"
        every { deleteFile(any()) } returns "Deleted"
    }
    private val provider = SkillToolProvider(skillManager)
    private val enabled = GenerationContext(accessSkills = true)

    @Test
    fun definitionsExposeExactlyFiveSavedSkillTools() {
        val names = provider.definitions(enabled).map { it.function.name }
        assertEquals(
            listOf(
                "list_skill_files",
                "read_skill_file",
                "create_skill_file",
                "edit_skill_file",
                "delete_skill_file",
            ),
            names,
        )
        assertFalse(names.any { it.contains("active") })
    }

    @Test
    fun disabledAccessHidesAndRejectsTools() = runTest {
        val disabled = enabled.copy(accessSkills = false)
        assertTrue(provider.definitions(disabled).isEmpty())
        assertTrue(
            provider.execute("list_skill_files", "{}", disabled)
                .contains("disabled", ignoreCase = true),
        )
    }

    @Test
    fun listReturnsOnlyCatalogMetadata() = runTest {
        val result = provider.execute("list_skill_files", "{}", enabled)
        assertTrue(result.contains("review.md"))
        assertTrue(result.contains("Review changes"))
        assertFalse(result.contains("skill body"))
    }

    @Test
    fun readLoadsBodyOnDemand() = runTest {
        assertEquals(
            "skill body",
            provider.execute(
                "read_skill_file",
                """{"name":"review.md"}""",
                enabled,
            ),
        )
    }

    @Test
    fun editSupportsUniquePatch() = runTest {
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","old_string":"old","new_string":"new"}""",
                enabled,
            ),
        )
        verify {
            skillManager.editFile(
                name = "review.md",
                content = null,
                newName = null,
                description = null,
                oldString = "old",
                newString = "new",
            )
        }
    }

    @Test
    fun handlesOnlySkillTools() {
        assertTrue(provider.handles("read_skill_file"))
        assertFalse(provider.handles("update_active_memory"))
        assertFalse(provider.handles("web_search"))
    }
}
