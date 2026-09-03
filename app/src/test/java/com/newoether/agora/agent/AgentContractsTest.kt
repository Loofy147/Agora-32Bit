package com.newoether.agora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContractsTest {
    private val readPolicy = AgentPolicy(
        allowedCapabilities = setOf(
            AgentCapability.MODEL_INFERENCE,
            AgentCapability.WEB_SEARCH,
            AgentCapability.RAG_SEARCH,
        ),
        maxSteps = 8,
        timeoutMs = 60_000L,
    )

    @Test
    fun `read-only task is allowed when capabilities are permitted`() {
        val task = AgentTask(
            missionId = "m1",
            name = "search",
            objective = "Find evidence",
            requiredCapabilities = setOf(AgentCapability.WEB_SEARCH),
        )
        assertEquals(PolicyDecision.Allow, AgentPolicyEvaluator.evaluate(task, readPolicy))
    }

    @Test
    fun `unsupported capability is denied`() {
        val task = AgentTask(
            missionId = "m1",
            name = "shell",
            objective = "Run a command",
            requiredCapabilities = setOf(AgentCapability.REMOTE_SHELL),
        )
        val result = AgentPolicyEvaluator.evaluate(task, readPolicy)
        assertTrue(result is PolicyDecision.Deny)
        assertTrue((result as PolicyDecision.Deny).reason.contains("REMOTE_SHELL"))
    }

    @Test
    fun `side effect requires approval`() {
        val policy = readPolicy.copy(
            allowedCapabilities = readPolicy.allowedCapabilities + AgentCapability.FILE_WRITE,
        )
        val task = AgentTask(
            missionId = "m1",
            name = "write",
            objective = "Write artifact",
            requiredCapabilities = setOf(AgentCapability.FILE_WRITE),
            effectLevel = EffectLevel.REVERSIBLE,
        )
        assertEquals(
            PolicyDecision.ApprovalRequired("Side effect requires approval"),
            AgentPolicyEvaluator.evaluate(task, policy),
        )
    }

    @Test
    fun `destructive effect is denied`() {
        val policy = readPolicy.copy(
            allowedCapabilities = readPolicy.allowedCapabilities + AgentCapability.FILE_WRITE,
        )
        val task = AgentTask(
            missionId = "m1",
            name = "destroy",
            objective = "Delete artifact",
            requiredCapabilities = setOf(AgentCapability.FILE_WRITE),
            effectLevel = EffectLevel.DESTRUCTIVE,
        )
        assertEquals(
            PolicyDecision.Deny("Destructive effects are disabled by policy"),
            AgentPolicyEvaluator.evaluate(task, policy),
        )
    }

    @Test
    fun `mission transition graph rejects invalid transitions`() {
        assertTrue(MissionStateMachine.canTransition(MissionStatus.PROPOSED, MissionStatus.PLANNED))
        assertTrue(MissionStateMachine.canTransition(MissionStatus.VERIFYING, MissionStatus.SUCCEEDED))
        assertTrue(!MissionStateMachine.canTransition(MissionStatus.SUCCEEDED, MissionStatus.EXECUTING))
        assertTrue(!MissionStateMachine.canTransition(MissionStatus.PROPOSED, MissionStatus.SUCCEEDED))
    }

    @Test
    fun `increment step enforces the smaller mission and policy budget`() {
        val mission = Mission(
            spec = MissionSpec(objective = "bounded", maxSteps = 3),
            status = MissionStatus.EXECUTING,
            stepCount = 2,
        )
        val next = MissionStateMachine.incrementStep(mission, readPolicy)
        assertEquals(3, next.stepCount)
    }

    @Test
    fun `blank mission objective produces a validation error`() {
        val errors = AgentPolicyEvaluator.validateMission(
            MissionSpec(objective = "", maxSteps = 1, timeoutMs = 1),
            readPolicy,
        )
        assertTrue(errors.any { it.contains("objective") })
    }
}
