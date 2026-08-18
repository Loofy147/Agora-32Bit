package com.newoether.agora.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SkillManager(context: Context) {
    private val skillDir = File(context.filesDir, "skill_db").also { it.mkdirs() }
    private val metaFile = File(skillDir, "skill_meta.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()

    data class SkillFileInfo(
        val name: String,
        val description: String = "",
    )

    @Synchronized
    private fun loadMeta(): MutableMap<String, String> =
        if (metaFile.exists()) {
            runCatching {
                json.decodeFromString<MutableMap<String, String>>(metaFile.readText())
            }.getOrDefault(mutableMapOf())
        } else {
            mutableMapOf()
        }

    @Synchronized
    private fun saveMeta(meta: Map<String, String>) {
        metaFile.writeText(json.encodeToString(meta))
    }

    @Synchronized
    fun listFiles(): List<SkillFileInfo> {
        val meta = loadMeta()
        return skillDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { SkillFileInfo(it.name, meta[it.name].orEmpty()) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    @Synchronized
    fun catalog(): String {
        val files = listFiles()
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("<available_skills>")
            appendLine(
                "Use the skill file tools to read a relevant skill before following it. " +
                    "Only names and descriptions are preloaded."
            )
            files.forEach { file ->
                append("- ")
                append(file.name)
                if (file.description.isNotBlank()) {
                    append(": ")
                    append(file.description.replace('\n', ' ').trim())
                }
                appendLine()
            }
            append("</available_skills>")
        }
    }

    @Synchronized
    fun getDescription(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) return ""
        return loadMeta()[file.name].orEmpty()
    }

    @Synchronized
    fun getMetaJson(): String = if (metaFile.exists()) metaFile.readText() else "{}"

    @Synchronized
    fun saveMetaJson(jsonString: String) {
        val temporary = File(metaFile.parentFile, metaFile.name + ".tmp")
        temporary.writeText(jsonString)
        if (!temporary.renameTo(metaFile)) {
            metaFile.writeText(jsonString)
            temporary.delete()
        }
        _catalogRevision.value += 1
    }

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String = ""): String {
        val file = resolveFile(name)
        require(!file.exists()) { "File already exists: ${file.name}" }
        file.writeText(content)
        if (description.isNotBlank()) {
            val meta = loadMeta()
            meta[file.name] = description
            saveMeta(meta)
        }
        _catalogRevision.value += 1
        return "Created ${file.name}"
    }

    @Synchronized
    fun editFile(
        name: String,
        content: String? = null,
        newName: String? = null,
        description: String? = null,
        oldString: String? = null,
        newString: String? = null,
    ): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        require(content == null || oldString == null) {
            "content and old_string are mutually exclusive"
        }
        val meta = loadMeta()
        var target = file
        if (oldString != null) {
            val existing = file.readText()
            val matches = existing.countOccurrences(oldString)
            require(matches == 1) {
                if (matches == 0) "old_string not found in ${file.name}"
                else "old_string matches $matches times in ${file.name}; it must be unique"
            }
            file.writeText(existing.replace(oldString, newString.orEmpty()))
        } else if (content != null) {
            file.writeText(content)
        }
        if (newName != null && resolveFile(newName).name != file.name) {
            val renamed = resolveFile(newName)
            require(!renamed.exists()) { "Target file already exists: ${renamed.name}" }
            require(file.renameTo(renamed)) { "Unable to rename ${file.name}" }
            meta.remove(file.name)?.let { meta[renamed.name] = it }
            target = renamed
        }
        if (description != null) {
            if (description.isBlank()) meta.remove(target.name)
            else meta[target.name] = description
        }
        saveMeta(meta)
        _catalogRevision.value += 1
        return "Updated ${target.name}"
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        require(file.delete()) { "Unable to delete ${file.name}" }
        val meta = loadMeta()
        meta.remove(file.name)
        saveMeta(meta)
        _catalogRevision.value += 1
        return "Deleted ${file.name}"
    }

    private fun String.countOccurrences(value: String): Int {
        require(value.isNotEmpty()) { "old_string must not be empty" }
        var count = 0
        var start = 0
        while (true) {
            val match = indexOf(value, start)
            if (match < 0) return count
            count += 1
            start = match + value.length
        }
    }

    private fun resolveFile(name: String): File {
        val sanitized = name.replace(Regex("""[/\\]"""), "_")
        val file = File(skillDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val directoryPath = skillDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.parent == directoryPath) { "Invalid file name: $name" }
        return file
    }
}
