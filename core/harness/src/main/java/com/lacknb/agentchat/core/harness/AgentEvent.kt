package com.lacknb.agentchat.core.harness

data class AgentEvent(
    val id: String,
    val type: AgentEventType,
    val summary: String,
    val payloadJson: String? = null,
    val riskLevel: RiskLevel? = null,
    val parentEventId: String? = null,
    val createdAtMillis: Long,
)

enum class AgentEventType {
    Plan,
    Action,
    Observation,
    Memory,
    UserApproval,
    Final,
    Error,
}
