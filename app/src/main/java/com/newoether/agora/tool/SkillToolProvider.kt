package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.SkillManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class SkillToolProvider(
    private val skillManager: SkillManager,
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSkills) return emptyList()
        return listOf(
            tool(
                name = "list_skill_files",
                description = "List saved skill files with their names and compact descriptions.",
            ),
            tool(
                name = "read_skill_file",
                description = "Read one or more saved skill files. Read a relevant skill before applying it.",
                properties = mapOf(
                    "name" to ToolProperty("string", "One skill file name."),
                    "names" to ToolProperty(
                        "array",
                        "Multiple skill file names.",
                        items = ToolProperty("string", "A skill file name."),
                    ),
                ),
            ),
            tool(
                name = "create_skill_file",
                description = "Create a saved Markdown skill file.",
                properties = mapOf(
                    "name" to ToolProperty("string", "The skill file name."),
                    "content" to ToolProperty("string", "The complete Markdown instructions."),
                    "description" to ToolProperty("string", "A compact catalog description."),
                ),
                required = listOf("name", "content"),
            ),
            tool(
                name = "edit_skill_file",
                description = "Edit, patch, rename, or describe a saved skill file. old_string must match exactly once and is mutually exclusive with content.",
                properties = mapOf(
                    "name" to ToolProperty("string", "The current file name."),
                    "content" to ToolProperty("string", "A complete replacement."),
                    "old_string" to ToolProperty("string", "Exact unique text to replace."),
                    "new_string" to ToolProperty("string", "Replacement for old_string."),
                    "new_name" to ToolProperty("string", "An optional new file name."),
                    "description" to ToolProperty("string", "An optional compact description."),
                ),
                required = listOf("name"),
            ),
            tool(
                name = "delete_skill_file",
                description = "Delete a saved skill file.",
                properties = mapOf(
                    "name" to ToolProperty("string", "The skill file name to delete."),
                ),
                required = listOf("name"),
            ),
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        if (!ctx.accessSkills) return@withContext "Error: Skill access is disabled."
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            arguments.ifBlank { "{}" },
        )
        fun arg(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()
        when (name) {
            "list_skill_files" -> buildJsonObject {
                put("type", "list_skill_files")
                putJsonArray("files") {
                    skillManager.listFiles().forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.name)
                                put("description", file.description)
                            },
                        )
                    }
                }
            }.toString()
            "read_skill_file" -> {
                val names = (args["names"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
                when {
                    names.isNotEmpty() -> names.joinToString("\n\n") { fileName ->
                        "--- $fileName ---\n${skillManager.readFile(fileName)}"
                    }
                    arg("name").isNotBlank() -> skillManager.readFile(arg("name"))
                    else -> "Error: Provide name or names."
                }
            }
            "create_skill_file" -> skillManager.createFile(
                name = arg("name"),
                content = arg("content"),
                description = arg("description"),
            )
            "edit_skill_file" -> {
                val content = arg("content").takeIf { args.containsKey("content") }
                val oldString = arg("old_string").takeIf { args.containsKey("old_string") }
                val newString = arg("new_string").takeIf { args.containsKey("new_string") }
                val newName = arg("new_name").takeIf(String::isNotBlank)
                val description = arg("description").takeIf { args.containsKey("description") }
                when {
                    content != null && oldString != null ->
                        "Error: content and old_string are mutually exclusive."
                    oldString != null && newString == null ->
                        "Error: old_string requires new_string."
                    content == null && oldString == null && newName == null && description == null ->
                        "Error: Provide content, old_string, new_name, or description."
                    else -> skillManager.editFile(
                        name = arg("name"),
                        content = content,
                        newName = newName,
                        description = description,
                        oldString = oldString,
                        newString = newString,
                    )
                }
            }
            "delete_skill_file" -> skillManager.deleteFile(arg("name"))
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty> = emptyMap(),
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ),
    )

    private companion object {
        val TOOL_NAMES = setOf(
            "list_skill_files",
            "read_skill_file",
            "create_skill_file",
            "edit_skill_file",
            "delete_skill_file",
        )
    }
}
