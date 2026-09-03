package com.newoether.agora.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Minimal external capability result. No Run, policy, persistence, or UI ownership. */
@Serializable
data class AceCapabilityResult(
    val newInsights: List<JsonObject> = emptyList(),
    val playbookEntries: List<JsonObject> = emptyList(),
    val rawResponse: String = "",
)

/**
 * Executes the already-declared ACE HTTP capability.
 *
 * Policy admission remains the caller's responsibility. This class only performs the external
 * side effect and normalizes the successful response into a transport-neutral result.
 */
class AceCapabilityExecutor(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun run(baseUrl: String, apiKey: String, task: String): Result<AceCapabilityResult> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("ACE base URL must not be blank"))
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("ACE API key must not be blank"))
            if (task.isBlank()) return@withContext Result.failure(IllegalArgumentException("ACE task must not be blank"))

            val endpoint = baseUrl.trimEnd('/') + "/run-ace/"
            val body = "{\"task\":${json.encodeToString(kotlinx.serialization.serializer<String>(), task)}}"
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .post(body)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("ACE request failed: HTTP ${response.code}")
                    }
                    val payload = json.parseToJsonElement(raw).jsonObject
                    val insights = payload["new_insights"]?.let { element ->
                        element.jsonArray.mapNotNull { elementValue -> elementValue as? JsonObject }
                    }.orEmpty()
                    val entries = payload["playbook_entries"]?.let { element ->
                        element.jsonArray.mapNotNull { elementValue -> elementValue as? JsonObject }
                    }.orEmpty()
                    AceCapabilityResult(
                        newInsights = insights,
                        playbookEntries = entries,
                        rawResponse = raw,
                    )
                }
            }
        }
}
