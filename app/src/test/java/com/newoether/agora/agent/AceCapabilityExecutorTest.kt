package com.newoether.agora.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceCapabilityExecutorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ace response preserves structured insight and playbook objects`() {
        val raw = """
            {
              "new_insights": [{"content":"keep evidence explicit"}],
              "playbook_entries": [{"content":"verify before claiming success"}]
            }
        """.trimIndent()

        val payload = json.parseToJsonElement(raw).jsonObject
        val result = AceCapabilityResult(
            newInsights = payload["new_insights"]!!.jsonArray.map { it.jsonObject },
            playbookEntries = payload["playbook_entries"]!!.jsonArray.map { it.jsonObject },
            rawResponse = raw,
        )

        assertEquals("keep evidence explicit", result.newInsights.single()["content"]?.toString()?.trim('"'))
        assertEquals("verify before claiming success", result.playbookEntries.single()["content"]?.toString()?.trim('"'))
        assertTrue(result.rawResponse.contains("new_insights"))
    }

    @Test
    fun `ace capability is separately gateable from generic model inference`() {
        val mission = MissionSpec(
            objective = "Improve future execution context",
            requestedCapabilities = setOf(AgentCapability.CONTEXT_ENGINEERING),
            maxSteps = 2,
            timeoutMs = 10_000L,
        )
        val defaultPolicy = AgentPolicy(maxSteps = 2, timeoutMs = 10_000L)

        val violations = AgentPolicyEvaluator.validateMission(mission, defaultPolicy)

        assertTrue(violations.any { it.contains("CONTEXT_ENGINEERING") })
    }
}
