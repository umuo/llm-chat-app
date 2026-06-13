package com.lacknb.agentchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lacknb.agentchat.core.designsystem.AgentChatTheme
import com.lacknb.agentchat.core.harness.AgentEvent
import com.lacknb.agentchat.core.harness.AgentEventType
import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.model.ChatMessage
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionMessage
import com.lacknb.agentchat.core.network.ChatCompletionRequest
import com.lacknb.agentchat.core.network.ChatCompletionStreamEvent
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.ChatCompletionToolCall
import com.lacknb.agentchat.core.network.ChatCompletionToolCallFunction
import com.lacknb.agentchat.core.network.ChatCompletionsClient
import com.lacknb.agentchat.core.network.OpenAiChatCompletionsClient
import com.lacknb.agentchat.core.provider.ProviderRepository
import com.lacknb.agentchat.feature.chat.ChatRoute
import com.lacknb.agentchat.feature.settings.SettingsRoute
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerRepository = ProviderRepository(applicationContext)
        val chatClient = OpenAiChatCompletionsClient()
        val agentWorkspace = File(applicationContext.filesDir, "agent_workspace")

        setContent {
            AgentChatTheme {
                AgentChatApp(
                    providerRepository = providerRepository,
                    chatClient = chatClient,
                    agentWorkspace = agentWorkspace,
                )
            }
        }
    }
}

@Composable
private fun AgentChatApp(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
) {
    val navController = rememberNavController()
    val providerSettings by providerRepository.settings.collectAsState()

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Chat.route,
    ) {
        composable(TopLevelDestination.Chat.route) {
            ChatRoute(
                providerSettings = providerSettings,
                onOpenSettings = { navController.navigate(TopLevelDestination.Settings.route) },
                onSendMessage = { messages, onDelta, onToolCallDelta ->
                    streamChatCompletion(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        messages = messages,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
                onRunAgent = { goal, history, onEvent, onDelta, onToolCallDelta ->
                    runAgentChatCompletion(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        agentWorkspace = agentWorkspace,
                        goal = goal,
                        history = history,
                        onEvent = onEvent,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
            )
        }
        composable(TopLevelDestination.Settings.route) {
            SettingsRoute(
                settings = providerSettings,
                onBackToChat = { navController.popBackStack() },
                onSaveProvider = providerRepository::saveProvider,
                onTestConnection = {
                    testChatCompletionConnection(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                    )
                },
            )
        }
    }
}

private suspend fun runAgentChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
    goal: String,
    history: List<ChatMessage>,
    onEvent: (AgentEvent) -> Unit,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit,
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API key is not configured")
    var eventIndex = 0
    val conversation = buildAgentMessages(goal, history).toMutableList()

    fun emitEvent(
        type: AgentEventType,
        summary: String,
        payloadJson: String? = null,
        riskLevel: RiskLevel? = null,
    ) {
        onEvent(
            AgentEvent(
                id = "agent-${System.currentTimeMillis()}-${eventIndex++}",
                type = type,
                summary = summary,
                payloadJson = payloadJson,
                riskLevel = riskLevel,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    emitEvent(
        type = AgentEventType.Plan,
        summary = "Agent mode: decide whether tools are needed, execute them, then continue until a final answer.",
    )

    repeat(MaxAgentToolTurns) { turnIndex ->
        var emittedStreamingObservation = false
        val assistantContent = StringBuilder()
        val toolCalls = mutableListOf<ChatToolCall>()
        val announcedToolCallIndexes = mutableSetOf<Int>()

        emitEvent(
            type = AgentEventType.Action,
            summary = "Calling ${profile.defaultModel} with ${agentTools().size} available tools.",
        )

        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = conversation,
            stream = true,
            tools = agentTools(),
            toolChoice = "auto",
        )

        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Completed -> Unit
                is ChatCompletionStreamEvent.Delta -> {
                    if (!emittedStreamingObservation) {
                        emitEvent(
                            type = AgentEventType.Observation,
                            summary = "Model response started streaming.",
                        )
                        emittedStreamingObservation = true
                    }
                    assistantContent.append(event.content)
                    onDelta(event.content)
                }
                is ChatCompletionStreamEvent.ToolCallDelta -> {
                    if (announcedToolCallIndexes.add(event.delta.index)) {
                        emitEvent(
                            type = AgentEventType.Action,
                            summary = "Model requested tool: ${event.delta.functionName ?: "function_${event.delta.index}"}",
                            payloadJson = event.delta.arguments.takeIf { it.isNotBlank() },
                            riskLevel = event.delta.functionName.toolRiskLevel(),
                        )
                    }
                    val toolDelta = ChatToolCall(
                        index = event.delta.index,
                        id = event.delta.id,
                        name = event.delta.functionName,
                        arguments = event.delta.arguments,
                    )
                    toolCalls.mergeInPlace(toolDelta)
                    onToolCallDelta(toolDelta.copy(index = turnIndex * 100 + toolDelta.index))
                }
                is ChatCompletionStreamEvent.Failed -> {
                    emitEvent(
                        type = AgentEventType.Error,
                        summary = event.message,
                    )
                    throw IllegalStateException(event.message, event.cause)
                }
            }
        }

        if (toolCalls.isEmpty()) {
            emitEvent(
                type = AgentEventType.Final,
                summary = "Agent finished without needing another tool call.",
            )
            return@runCatching
        }

        conversation += ChatCompletionMessage(
            role = "assistant",
            content = assistantContent.toString(),
            toolCalls = toolCalls.sortedBy { it.index }.map { call ->
                ChatCompletionToolCall(
                    id = call.id ?: "tool_${turnIndex}_${call.index}",
                    function = ChatCompletionToolCallFunction(
                        name = call.name ?: "unknown_tool",
                        arguments = call.arguments,
                    )
                )
            },
        )

        toolCalls.sortedBy { it.index }.forEach { call ->
            val toolName = call.name ?: "unknown_tool"
            val toolResult = executeAgentTool(
                name = toolName,
                argumentsJson = call.arguments,
                profile = profile,
                apiKey = apiKey,
                chatClient = chatClient,
                agentWorkspace = agentWorkspace,
            )

            emitEvent(
                type = AgentEventType.Observation,
                summary = "Tool finished: $toolName",
                payloadJson = toolResult.take(1_200),
                riskLevel = toolName.toolRiskLevel(),
            )

            conversation += ChatCompletionMessage(
                role = "tool",
                toolCallId = call.id ?: "tool_${turnIndex}_${call.index}",
                content = toolResult,
            )
        }
    }

    emitEvent(
        type = AgentEventType.Error,
        summary = "Agent stopped after $MaxAgentToolTurns tool rounds to avoid an infinite loop.",
    )
    onDelta(
        "\n\nI reached the tool-round limit before producing a final answer. Try narrowing the task or asking me to continue.",
    )
}

private fun MutableList<ChatToolCall>.mergeInPlace(delta: ChatToolCall) {
    val existingIndex = indexOfFirst { it.index == delta.index }
    if (existingIndex < 0) {
        add(delta)
    } else {
        val existing = this[existingIndex]
        this[existingIndex] = existing.copy(
            id = delta.id ?: existing.id,
            name = delta.name ?: existing.name,
            arguments = existing.arguments + delta.arguments,
        )
    }
}

private suspend fun executeAgentTool(
    name: String,
    argumentsJson: String,
    profile: com.lacknb.agentchat.core.model.ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
): String {
    val args = parseToolArguments(argumentsJson)
    return runCatching {
        when (name) {
            "plan_agent" -> runPlanningSubAgent(
                objective = args.optString("objective").ifBlank { args.optString("goal") },
                context = args.optString("context"),
                profile = profile,
                apiKey = apiKey,
                chatClient = chatClient,
            )
            "read_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                require(file.exists() && file.isFile) { "File does not exist: ${args.getString("path")}" }
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("content", file.readText().take(MaxToolResultChars))
                    .toString()
            }
            "write_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                file.parentFile?.mkdirs()
                file.writeText(args.getString("content"))
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("bytes", file.length())
                    .toString()
            }
            "edit_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                require(file.exists() && file.isFile) { "File does not exist: ${args.getString("path")}" }
                val oldText = args.getString("old_text")
                val newText = args.getString("new_text")
                val original = file.readText()
                require(oldText in original) { "old_text was not found in ${args.getString("path")}" }
                val updated = if (args.optBoolean("replace_all", false)) {
                    original.replace(oldText, newText)
                } else {
                    original.replaceFirst(oldText, newText)
                }
                file.writeText(updated)
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("changed", true)
                    .toString()
            }
            "record_memory_candidate" -> {
                JSONObject()
                    .put("ok", true)
                    .put("status", "candidate_recorded_for_review")
                    .put("content", args.optString("content"))
                    .put("reason", args.optString("reason"))
                    .put("sensitivity", args.optString("sensitivity"))
                    .toString()
            }
            else -> {
                JSONObject()
                    .put("ok", false)
                    .put("error", "Unknown tool: $name")
                    .toString()
            }
        }
    }.getOrElse { error ->
        JSONObject()
            .put("ok", false)
            .put("error", error.message ?: "Tool execution failed")
            .toString()
    }
}

private suspend fun runPlanningSubAgent(
    objective: String,
    context: String,
    profile: com.lacknb.agentchat.core.model.ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
): String {
    require(objective.isNotBlank()) { "objective is required" }
    val plan = StringBuilder()
    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = "You are a planning sub-agent. Produce a compact, actionable Markdown plan with assumptions and risks only when useful.",
            ),
            ChatCompletionMessage(
                role = "user",
                content = buildString {
                    appendLine("Objective: $objective")
                    if (context.isNotBlank()) appendLine("Context: $context")
                },
            ),
        ),
        stream = true,
    )

    withTimeout(30_000) {
        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Completed -> Unit
                is ChatCompletionStreamEvent.Delta -> plan.append(event.content)
                is ChatCompletionStreamEvent.ToolCallDelta -> Unit
                is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
            }
        }
    }

    return JSONObject()
        .put("ok", true)
        .put("plan", plan.toString().take(MaxToolResultChars))
        .toString()
}

private fun parseToolArguments(argumentsJson: String): JSONObject {
    return runCatching {
        JSONObject(argumentsJson.ifBlank { "{}" })
    }.getOrDefault(JSONObject())
}

private fun resolveAgentWorkspaceFile(workspace: File, path: String): File {
    val normalized = path.trim().replace('\\', '/')
    require(normalized.isNotBlank()) { "path is required" }
    require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) {
        "Only relative paths inside the agent workspace are allowed"
    }
    workspace.mkdirs()
    val root = workspace.canonicalFile
    val file = File(root, normalized).canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
        "Path escapes the agent workspace"
    }
    return file
}

private fun String?.toolRiskLevel(): RiskLevel {
    return when (this) {
        "write_file", "edit_file" -> RiskLevel.Medium
        "read_file", "plan_agent", "record_memory_candidate" -> RiskLevel.Low
        else -> RiskLevel.Low
    }
}

private const val MaxAgentToolTurns = 5
private const val MaxToolResultChars = 12_000

private suspend fun streamChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    messages: List<ChatMessage>,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit = {},
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API key is not configured")

    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = messages
            .filter { message ->
                message.status != MessageStatus.Streaming &&
                    message.content.isNotBlank() &&
                    message.role in setOf(MessageRole.User, MessageRole.Assistant, MessageRole.System)
            }
            .map { message ->
                ChatCompletionMessage(
                    role = message.role.toChatCompletionRole(),
                    content = message.content,
                )
            },
        stream = true,
    )

    chatClient.streamChatCompletion(
        profile = profile,
        apiKey = apiKey,
        request = request,
    ).collect { event ->
        when (event) {
            is ChatCompletionStreamEvent.Completed -> Unit
            is ChatCompletionStreamEvent.Delta -> onDelta(event.content)
            is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
            is ChatCompletionStreamEvent.ToolCallDelta -> {
                onToolCallDelta(
                    ChatToolCall(
                        index = event.delta.index,
                        id = event.delta.id,
                        name = event.delta.functionName,
                        arguments = event.delta.arguments,
                    ),
                )
            }
        }
    }
}

private fun buildAgentMessages(
    goal: String,
    history: List<ChatMessage>,
): List<ChatCompletionMessage> {
    val systemPrompt = """
        You are AgentChat running in Agent mode.
        Use ReAct internally: decide whether a tool is needed, call exactly the useful tool when needed, observe the result, then continue.
        If no tool is needed, answer directly and do not call a tool.
        For complex or ambiguous work, call plan_agent to create a plan before continuing.
        read_file, write_file, and edit_file operate only inside the app's private agent workspace.
        After all needed tool observations are available, provide the final answer in Markdown.
    """.trimIndent()
    val recentHistory = history
        .filter { message ->
            message.status == MessageStatus.Complete &&
                message.id != "welcome" &&
                message.content.isNotBlank() &&
                message.role in setOf(MessageRole.User, MessageRole.Assistant)
        }
        .takeLast(8)
        .map { message ->
            ChatCompletionMessage(
                role = message.role.toChatCompletionRole(),
                content = message.content,
            )
        }

    val goalMessage = ChatCompletionMessage(role = "user", content = goal)
    val conversation = if (recentHistory.lastOrNull() == goalMessage) {
        recentHistory
    } else {
        recentHistory + goalMessage
    }

    return listOf(ChatCompletionMessage(role = "system", content = systemPrompt)) + conversation
}

private fun agentTools(): List<ChatCompletionTool> {
    return listOf(
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "plan_agent",
                description = "Ask a planning sub-agent to create a compact plan for a complex task. Use this as a tool, not as a user-visible mode.",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "objective": {"type": "string"},
                        "context": {"type": "string"}
                      },
                      "required": ["objective"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "read_file",
                description = "Read a UTF-8 text file from the app-private agent workspace.",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"}
                      },
                      "required": ["path"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "write_file",
                description = "Write a UTF-8 text file inside the app-private agent workspace, creating parent directories when needed.",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"}
                      },
                      "required": ["path", "content"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "edit_file",
                description = "Replace text in a UTF-8 file inside the app-private agent workspace.",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"},
                        "old_text": {"type": "string"},
                        "new_text": {"type": "string"},
                        "replace_all": {"type": "boolean"}
                      },
                      "required": ["path", "old_text", "new_text"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "record_memory_candidate",
                description = "Propose a memory item that should be shown to the user for approval before saving.",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "content": {"type": "string"},
                        "reason": {"type": "string"},
                        "sensitivity": {"type": "string", "enum": ["low", "medium", "high"]}
                      },
                      "required": ["content", "reason", "sensitivity"]
                    }
                """.trimIndent(),
            ),
        ),
    )
}

private suspend fun testChatCompletionConnection(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
): Result<Unit> = runCatching {
    withTimeout(30_000) {
        val profile = providerRepository.currentProfile()
        val apiKey = providerRepository.currentApiKey()
            ?: error("API key is not configured")
        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = "Reply with ok.",
                ),
            ),
            stream = true,
            maxTokens = 4,
        )

        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Failed -> {
                    throw IllegalStateException(event.message, event.cause)
                }
                is ChatCompletionStreamEvent.Completed,
                is ChatCompletionStreamEvent.Delta,
                is ChatCompletionStreamEvent.ToolCallDelta -> Unit
            }
        }
    }
}

private fun MessageRole.toChatCompletionRole(): String {
    return when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.System -> "system"
        MessageRole.Tool -> "tool"
    }
}

private enum class TopLevelDestination(val route: String) {
    Chat(route = "chat"),
    Settings(route = "settings"),
}
