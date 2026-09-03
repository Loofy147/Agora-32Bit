package com.newoether.agora.agent

import kotlinx.serialization.Serializable
import java.util.UUID

/** Stable capability vocabulary used by the agent control plane. */
@Serializable
enum class AgentCapability {
    MODEL_INFERENCE,
    WEB_SEARCH,
    WEB_FETCH,
    CODE_EXECUTION,
    FILE_READ,
    FILE_WRITE,
    SHELL_EXECUTION,
    REMOTE_SHELL,
    MEMORY_READ,
    MEMORY_WRITE,
    RAG_SEARCH,
    MCP,
    IMAGE_GENERATION,
    AUTOMATION,
}

/** Lifecycle of a mission. Transitions are validated by [MissionStateMachine]. */
@Serializable
enum class MissionStatus {
    PROPOSED,
    PLANNED,
    EXECUTING,
    WAITING_TOOL,
    OBSERVING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
}

/** Classification of evidence produced by an agent run. */
@Serializable
enum class EvidenceKind {
    OBSERVATION,
    TOOL_RESULT,
    ARTIFACT,
    TEST_RESULT,
    VERIFICATION,
    ERROR,
    DECISION,
}

/** Side-effect level used by policy checks before an action executes. */
@Serializable
enum class EffectLevel {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
}

@Serializable
data class MissionSpec(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val constraints: List<String> = emptyList(),
    val requestedCapabilities: Set<AgentCapability> = setOf(AgentCapability.MODEL_INFERENCE),
    val preferredModel: String? = null,
    val maxSteps: Int = 32,
    val timeoutMs: Long = 15 * 60 * 1000L,
    val requireApprovalForSideEffects: Boolean = true,
)

@Serializable
data class Mission(
    val spec: MissionSpec,
    val status: MissionStatus = MissionStatus.PROPOSED,
    val stepCount: Int = 0,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val failureReason: String? = null,
)

@Serializable
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val missionId: String,
    val name: String,
    val objective: String,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val effectLevel: EffectLevel = EffectLevel.READ_ONLY,
    val dependsOn: List<String> = emptyList(),
)

@Serializable
data class Evidence(
    val id: String = UUID.randomUUID().toString(),
    val missionId: String,
    val taskId: String? = null,
    val kind: EvidenceKind,
    val source: String,
    val summary: String,
    val contentHash: String? = null,
    val verified: Boolean = false,
    val createdAtMs: Long,
)

@Serializable
data class AgentPolicy(
    val allowedCapabilities: Set<AgentCapability> = setOf(AgentCapability.MODEL_INFERENCE),
    val allowDestructiveEffects: Boolean = false,
    val requireApprovalForSideEffects: Boolean = true,
    val maxSteps: Int = 32,
    val timeoutMs: Long = 15 * 60 * 1000L,
)

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
    data class ApprovalRequired(val reason: String) : PolicyDecision
}

/** Pure policy gate. No Android, provider, or persistence dependency. */
object AgentPolicyEvaluator {
    fun evaluate(task: AgentTask, policy: AgentPolicy): PolicyDecision {
        val deniedCapabilities = task.requiredCapabilities - policy.allowedCapabilities
        if (deniedCapabilities.isNotEmpty()) {
            return PolicyDecision.Deny(
                "Capabilities not permitted: ${deniedCapabilities.sortedBy { it.name }.joinToString()}"
            )
        }
        if (task.effectLevel == EffectLevel.DESTRUCTIVE && !policy.allowDestructiveEffects) {
            return PolicyDecision.Deny("Destructive effects are disabled by policy")
        }
        if (
            task.effectLevel != EffectLevel.READ_ONLY &&
            policy.requireApprovalForSideEffects
        ) {
            return PolicyDecision.ApprovalRequired("Side effect requires approval")
        }
        return PolicyDecision.Allow
    }

    fun validateMission(spec: MissionSpec, policy: AgentPolicy): List<String> {
        val violations = mutableListOf<String>()
        if (spec.objective.isBlank()) violations += "objective must not be blank"
        if (spec.maxSteps <= 0) violations += "maxSteps must be > 0"
        if (spec.timeoutMs <= 0) violations += "timeoutMs must be > 0"
        if (spec.maxSteps > policy.maxSteps) violations += "mission maxSteps exceeds policy maxSteps"
        if (spec.timeoutMs > policy.timeoutMs) violations += "mission timeout exceeds policy timeout"
        val denied = spec.requestedCapabilities - policy.allowedCapabilities
        if (denied.isNotEmpty()) {
            violations += "requested capabilities not permitted: ${denied.sortedBy { it.name }.joinToString()}"
        }
        if (spec.requireApprovalForSideEffects && !policy.requireApprovalForSideEffects) {
            // Mission-level strictness is always acceptable; it simply opts into more approval.
        }
        return violations
    }
}

/** Deterministic state-transition validator for mission lifecycle. */
object MissionStateMachine {
    private val terminal = setOf(
        MissionStatus.SUCCEEDED,
        MissionStatus.FAILED,
        MissionStatus.BLOCKED,
        MissionStatus.CANCELLED,
    )

    fun canTransition(from: MissionStatus, to: MissionStatus): Boolean {
        if (from == to) return true
        if (from in terminal) return false
        return when (from) {
            MissionStatus.PROPOSED -> to == MissionStatus.PLANNED || to == MissionStatus.CANCELLED
            MissionStatus.PLANNED -> to == MissionStatus.EXECUTING || to == MissionStatus.BLOCKED || to == MissionStatus.CANCELLED
            MissionStatus.EXECUTING -> to == MissionStatus.WAITING_TOOL || to == MissionStatus.OBSERVING || to == MissionStatus.VERIFYING || to == MissionStatus.FAILED || to == MissionStatus.BLOCKED || to == MissionStatus.CANCELLED
            MissionStatus.WAITING_TOOL -> to == MissionStatus.OBSERVING || to == MissionStatus.FAILED || to == MissionStatus.BLOCKED || to == MissionStatus.CANCELLED
            MissionStatus.OBSERVING -> to == MissionStatus.EXECUTING || to == MissionStatus.VERIFYING || to == MissionStatus.FAILED || to == MissionStatus.BLOCKED || to == MissionStatus.CANCELLED
            MissionStatus.VERIFYING -> to == MissionStatus.SUCCEEDED || to == MissionStatus.EXECUTING || to == MissionStatus.FAILED || to == MissionStatus.BLOCKED || to == MissionStatus.CANCELLED
            MissionStatus.SUCCEEDED, MissionStatus.FAILED, MissionStatus.BLOCKED, MissionStatus.CANCELLED -> false
        }
    }

    fun transition(mission: Mission, next: MissionStatus, nowMs: Long): Mission {
        require(canTransition(mission.status, next)) {
            "Invalid mission transition: ${mission.status} -> $next"
        }
        val started = mission.startedAtMs ?: nowMs.takeIf {
            next == MissionStatus.EXECUTING || next == MissionStatus.WAITING_TOOL || next == MissionStatus.OBSERVING || next == MissionStatus.VERIFYING
        }
        val finished = nowMs.takeIf { next in terminal }
        return mission.copy(
            status = next,
            startedAtMs = started,
            finishedAtMs = finished ?: mission.finishedAtMs,
            failureReason = when (next) {
                MissionStatus.FAILED, MissionStatus.BLOCKED -> mission.failureReason
                else -> null
            },
        )
    }

    fun incrementStep(mission: Mission, policy: AgentPolicy): Mission {
        require(mission.status !in terminal) { "Cannot increment a terminal mission" }
        val nextCount = mission.stepCount + 1
        require(nextCount <= minOf(mission.spec.maxSteps, policy.maxSteps)) {
            "Mission step budget exceeded"
        }
        return mission.copy(stepCount = nextCount)
    }
}
