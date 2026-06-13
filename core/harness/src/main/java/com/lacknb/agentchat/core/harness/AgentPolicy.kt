package com.lacknb.agentchat.core.harness

interface AgentPolicy {
    suspend fun next(state: AgentState): AgentDecision
}

data class AgentState(
    val runId: AgentRunId,
    val goal: String,
    val events: List<AgentEvent>,
    val stepIndex: Int,
)

sealed interface AgentDecision {
    data class AskUser(val question: String) : AgentDecision
    data class CallTool(val call: ToolCall) : AgentDecision
    data class Respond(val message: String) : AgentDecision
    data class UpdatePlan(val plan: AgentPlan) : AgentDecision
    data class WriteMemory(val candidate: MemoryCandidate) : AgentDecision
}

data class ToolCall(
    val name: String,
    val argumentsJson: String,
    val riskLevel: RiskLevel,
)

enum class RiskLevel {
    Low,
    Medium,
    High,
}

data class AgentPlan(
    val title: String,
    val steps: List<PlanStep>,
    val assumptions: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
)

data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus,
    val expectedOutput: String,
    val requiredTools: List<String> = emptyList(),
)

enum class PlanStepStatus {
    Pending,
    Running,
    Completed,
    Blocked,
}

data class MemoryCandidate(
    val content: String,
    val type: String,
    val confidence: Float,
    val sensitivity: String,
    val reason: String,
)
