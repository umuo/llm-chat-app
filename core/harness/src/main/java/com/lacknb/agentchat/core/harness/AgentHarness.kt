package com.lacknb.agentchat.core.harness

import com.lacknb.agentchat.core.model.AgentPolicyType
import kotlinx.coroutines.flow.Flow

interface AgentHarness {
    fun observeRun(runId: AgentRunId): Flow<AgentRunState>
    suspend fun start(goal: String, config: AgentHarnessConfig): AgentRunId
    suspend fun submitUserApproval(runId: AgentRunId, approval: UserApproval)
    suspend fun pause(runId: AgentRunId)
    suspend fun resume(runId: AgentRunId)
    suspend fun cancel(runId: AgentRunId)
}

@JvmInline
value class AgentRunId(val value: String)

data class AgentHarnessConfig(
    val providerProfileId: String,
    val model: String,
    val policy: AgentPolicyType,
    val maxSteps: Int = 8,
    val maxToolCalls: Int = 5,
    val maxRunDurationSeconds: Int = 180,
    val permissionMode: PermissionMode = PermissionMode.AskForMediumAndHighRisk,
)

enum class PermissionMode {
    AskForAllTools,
    AskForMediumAndHighRisk,
    AutoApproveLowRisk,
}

data class AgentRunState(
    val id: AgentRunId,
    val goal: String,
    val status: AgentRunStatus,
    val events: List<AgentEvent>,
)

enum class AgentRunStatus {
    Created,
    Running,
    WaitingForUser,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

data class UserApproval(
    val eventId: String,
    val approved: Boolean,
    val note: String? = null,
)
