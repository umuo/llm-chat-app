package com.lacknb.agentchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lacknb.agentchat.core.designsystem.AgentChatTheme
import com.lacknb.agentchat.core.harness.AgentEvent
import com.lacknb.agentchat.core.harness.AgentEventType
import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.model.ChatMessage
import com.lacknb.agentchat.core.model.ChatContextSummary
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
import com.lacknb.agentchat.core.model.ProviderProfile
import com.lacknb.agentchat.core.model.RetrievalMode
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionMessage
import com.lacknb.agentchat.core.network.ChatCompletionRequest
import com.lacknb.agentchat.core.network.ChatCompletionStreamEvent
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.ChatCompletionToolCall
import com.lacknb.agentchat.core.network.ChatCompletionToolCallFunction
import com.lacknb.agentchat.core.network.ChatCompletionsClient
import com.lacknb.agentchat.core.network.OpenAiChatCompletionsClient
import com.lacknb.agentchat.core.memory.MemoryRepository
import com.lacknb.agentchat.core.prompts.ManagedPrompt
import com.lacknb.agentchat.core.prompts.PromptRepository
import com.lacknb.agentchat.core.provider.ProviderRepository
import com.lacknb.agentchat.feature.chat.ChatRoute
import com.lacknb.agentchat.feature.memory.MemoryManagementRoute
import com.lacknb.agentchat.feature.prompts.PromptManagementRoute
import com.lacknb.agentchat.feature.settings.SettingsRoute
import com.lacknb.agentchat.feature.settings.ToolCenterRoute
import com.lacknb.agentchat.tool.ToolRegistry
import com.lacknb.agentchat.tool.PlanAgentTool
import com.lacknb.agentchat.tool.ReadFileTool
import com.lacknb.agentchat.tool.WriteFileTool
import com.lacknb.agentchat.tool.EditFileTool
import com.lacknb.agentchat.tool.ManageMemoryTool
import com.lacknb.agentchat.tool.ManagePromptsTool
import com.lacknb.agentchat.tool.TavilySearchTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.core.view.WindowCompat
import com.lacknb.agentchat.core.network.mcp.McpClient
import com.lacknb.agentchat.tool.McpAgentTool

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentChat)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val providerRepository = ProviderRepository(applicationContext)
        val promptRepository = PromptRepository(applicationContext)
        val memoryRepository = MemoryRepository(applicationContext)
        val chatClient = OpenAiChatCompletionsClient()
        val agentWorkspace = File(applicationContext.filesDir, "agent_workspace")
        val toolRegistry = ToolRegistry(
            listOf(
                PlanAgentTool(providerRepository, chatClient),
                ReadFileTool(agentWorkspace),
                WriteFileTool(agentWorkspace),
                EditFileTool(agentWorkspace),
                ManageMemoryTool(
                    memoryRepository = memoryRepository,
                    providerRepository = providerRepository,
                    chatClient = chatClient,
                ),
                ManagePromptsTool(promptRepository),
                TavilySearchTool(providerRepository)
            )
        )

        setContent {
            AgentChatTheme {
                var showStartupSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1_400)
                    showStartupSplash = false
                }

                if (showStartupSplash) {
                    StartupSplashImage()
                } else {
                    AgentChatApp(
                        providerRepository = providerRepository,
                        promptRepository = promptRepository,
                        memoryRepository = memoryRepository,
                        chatClient = chatClient,
                        toolRegistry = toolRegistry,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupSplashImage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFCFCF8)),
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_agentchat),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun AgentChatApp(
    providerRepository: ProviderRepository,
    promptRepository: PromptRepository,
    memoryRepository: MemoryRepository,
    chatClient: ChatCompletionsClient,
    toolRegistry: ToolRegistry,
) {
    val navController = rememberNavController()
    val providerSettings by providerRepository.settings.collectAsState()

    LaunchedEffect(providerSettings.mcpServerUrl) {
        if (providerSettings.mcpServerUrl.isNotBlank()) {
            val mcpClient = McpClient()
            try {
                mcpClient.connect(providerSettings.mcpServerUrl)
                val mcpTools = mcpClient.getTools().map { McpAgentTool(mcpClient, it) }
                toolRegistry.addDynamicTools(mcpTools)
            } catch (e: Exception) {
                e.printStackTrace()
                toolRegistry.clearDynamicTools()
            }
        } else {
            toolRegistry.clearDynamicTools()
        }
    }

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Chat.route,
    ) {
        composable(TopLevelDestination.Chat.route) {
            ChatRoute(
                providerSettings = providerSettings,
                onOpenSettings = { navController.navigate(TopLevelDestination.Settings.route) },
                onOpenPrompts = { navController.navigate(TopLevelDestination.Prompts.route) },
                onOpenMemory = { navController.navigate(TopLevelDestination.Memory.route) },
                onOpenToolCenter = { navController.navigate(TopLevelDestination.ToolCenter.route) },
                onSendMessage = { messages, _, contextSummary, onContextSummaryChange, onDelta, onToolCallDelta ->
                    streamChatCompletion(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        messages = messages,
                        contextSummary = contextSummary,
                        onContextSummaryChange = onContextSummaryChange,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
                onRunAgent = { goal, history, useWebSearch, contextSummary, onContextSummaryChange, onEvent, onDelta, onToolCallDelta ->
                    runAgentChatCompletion(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        toolRegistry = toolRegistry,
                        goal = goal,
                        history = history,
                        useWebSearch = useWebSearch,
                        contextSummary = contextSummary,
                        onContextSummaryChange = onContextSummaryChange,
                        onEvent = onEvent,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
                onSaveLlmParameters = providerRepository::saveLlmParameters,
            )
        }
        composable(TopLevelDestination.Memory.route) {
            MemoryManagementRoute(
                repository = memoryRepository,
                onBackToChat = { navController.popBackStack() },
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
                onFetchModels = { baseUrl, apiKey ->
                    fetchProviderModels(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                    )
                },
                onSaveContextSettings = providerRepository::saveContextSettings,
            )
        }
        composable(TopLevelDestination.ToolCenter.route) {
            ToolCenterRoute(
                settings = providerSettings,
                onBackToChat = { navController.popBackStack() },
                onSaveMcpUrl = providerRepository::saveMcpUrl,
                onSaveTavilyApiKey = providerRepository::saveTavilyApiKey
            )
        }
        composable(TopLevelDestination.Prompts.route) {
            PromptManagementRoute(
                repository = promptRepository,
                onBackToChat = { navController.popBackStack() },
            )
        }
    }
}

private suspend fun runAgentChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    toolRegistry: ToolRegistry,
    goal: String,
    history: List<ChatMessage>,
    useWebSearch: Boolean = false,
    contextSummary: ChatContextSummary?,
    onContextSummaryChange: (ChatContextSummary?) -> Unit,
    onEvent: (AgentEvent) -> Unit,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit,
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")
    var eventIndex = 0

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

    val tavilyApiKey = providerRepository.settings.value.tavilyApiKey
    val hasSearchTool = useWebSearch && tavilyApiKey.isNotBlank()

    val managedHistory = prepareContextMessages(
        sourceMessages = history,
        existingSummary = contextSummary,
        mode = MessageContextMode.Agent,
        profile = profile,
        apiKey = apiKey,
        chatClient = chatClient,
        onContextSummaryChange = onContextSummaryChange,
    )
    val conversation = buildAgentMessages(goal, managedHistory, hasSearchTool).toMutableList()

    repeat(MaxAgentToolTurns) { turnIndex ->
        var emittedStreamingObservation = false
        val assistantContent = StringBuilder()
        var emittedAssistantContentLength = 0
        val toolCalls = mutableListOf<ChatToolCall>()
        val announcedToolCallIndexes = mutableSetOf<Int>()

        fun emitAssistantContentSegment() {
            if (assistantContent.length <= emittedAssistantContentLength) return
            val segment = assistantContent.substring(emittedAssistantContentLength)
            emittedAssistantContentLength = assistantContent.length
            if (segment.isNotBlank()) {
                emitEvent(
                    type = AgentEventType.Observation,
                    summary = AgentTextSegmentSummary,
                    payloadJson = segment,
                )
            }
        }

        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = conversation,
            stream = true,
            temperature = profile.temperature.toDouble(),
            topP = profile.topP.toDouble(),
            topK = if (profile.topK > 0) profile.topK else null,
            maxTokens = if (profile.maxTokens > 0) profile.maxTokens else null,
            tools = toolRegistry.getAllDeclarations().filter {
                it.function.name != "tavily_search" || hasSearchTool
            },
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
                            summary = "模型响应开始流式传输。",
                        )
                        emittedStreamingObservation = true
                    }
                    assistantContent.append(event.content)
                    onDelta(event.content)
                }
                is ChatCompletionStreamEvent.ToolCallDelta -> {
                    if (announcedToolCallIndexes.add(event.delta.index)) {
                        emitAssistantContentSegment()
                        emitEvent(
                            type = AgentEventType.Action,
                            summary = "模型请求工具：${event.delta.functionName ?: "函数_${event.delta.index}"}",
                            payloadJson = event.delta.arguments.takeIf { it.isNotBlank() },
                            riskLevel = toolRegistry.getRiskLevel(event.delta.functionName),
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
                summary = "智能体运行结束，无需调用其他工具。",
            )
            return@runCatching
        }

        emitAssistantContentSegment()

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
            val toolResult = toolRegistry.execute(toolName, call.arguments)

            emitEvent(
                type = AgentEventType.Observation,
                summary = "工具运行结束：$toolName",
                payloadJson = toolResult.take(1_200),
                riskLevel = toolRegistry.getRiskLevel(toolName),
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
        summary = "为避免死循环，智能体在执行 $MaxAgentToolTurns 轮工具调用后停止。",
    )
    onDelta(
        "\n\n我在得出最终答案之前达到了工具调用次数上限。请尝试缩小任务范围，或让我继续执行。",
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



private suspend fun testChatCompletionConnection(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
): Result<Unit> = runCatching {
    withTimeout(30_000) {
        val profile = providerRepository.currentProfile()
        val apiKey = providerRepository.currentApiKey()
            ?: error("API 密钥未配置")
        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = "请回复 ok。",
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

private suspend fun fetchProviderModels(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    baseUrl: String,
    apiKey: String,
): Result<List<String>> {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val resolvedApiKey = apiKey.trim().ifEmpty {
        providerRepository.currentApiKey().orEmpty()
    }
    if (normalizedBaseUrl.isBlank()) {
        return Result.failure(IllegalArgumentException("API Base URL is required"))
    }
    if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
        return Result.failure(IllegalArgumentException("API Base URL must start with http:// or https://"))
    }
    if (resolvedApiKey.isBlank()) {
        return Result.failure(IllegalStateException("API 密钥未配置"))
    }

    return chatClient.listModels(
        baseUrl = normalizedBaseUrl,
        apiKey = resolvedApiKey,
    )
}

private const val MaxAgentToolTurns = 5
private const val AgentTextSegmentSummary = "模型输出片段"

private enum class MessageContextMode {
    Chat,
    Agent,
}

private suspend fun prepareContextMessages(
    sourceMessages: List<ChatMessage>,
    existingSummary: ChatContextSummary?,
    mode: MessageContextMode,
    profile: ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
    onContextSummaryChange: (ChatContextSummary?) -> Unit,
): List<ChatMessage> {
    val eligibleMessages = sourceMessages.filter { message ->
        message.status != MessageStatus.Streaming &&
            message.id != "welcome" &&
            (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
            message.role in setOf(MessageRole.User, MessageRole.Assistant, MessageRole.System)
    }
    if (!profile.contextCompressionEnabled) {
        return trimToContextBudget(eligibleMessages, profile)
    }

    var contextMessages = applyExistingContextSummary(eligibleMessages, existingSummary)
    if (estimateTokens(contextMessages) <= contextInputBudget(profile)) {
        return contextMessages
    }

    val cutIndex = cutIndexForCompaction(eligibleMessages, profile)
    if (cutIndex <= 0) {
        return trimToContextBudget(contextMessages, profile)
    }

    val messagesToSummarize = eligibleMessages.take(cutIndex)
    val keptMessages = eligibleMessages.drop(cutIndex)
    contextMessages = try {
        val summary = summarizeContextMessages(
            messages = messagesToSummarize,
            previousSummary = existingSummary?.summary,
            mode = mode,
            profile = profile,
            apiKey = apiKey,
            chatClient = chatClient,
        )
        val nextSummary = ChatContextSummary(
            summary = summary,
            summarizedThroughMessageId = messagesToSummarize.lastOrNull()?.id,
            tokensBefore = estimateTokens(eligibleMessages),
            updatedAtMillis = System.currentTimeMillis(),
        )
        onContextSummaryChange(nextSummary)
        listOf(contextSummaryMessage(summary)) + keptMessages
    } catch (_: Exception) {
        keptMessages
    }

    return trimToContextBudget(contextMessages, profile)
}

private fun contextInputBudget(profile: ProviderProfile): Int {
    return (profile.contextWindowTokens - profile.contextReserveTokens).coerceAtLeast(1024)
}

private fun applyExistingContextSummary(
    messages: List<ChatMessage>,
    summary: ChatContextSummary?,
): List<ChatMessage> {
    if (summary == null || summary.summary.isBlank()) return messages
    val summarizedId = summary.summarizedThroughMessageId ?: return messages
    val summarizedIndex = messages.indexOfFirst { it.id == summarizedId }
    if (summarizedIndex < 0) return messages
    return listOf(contextSummaryMessage(summary.summary)) + messages.drop(summarizedIndex + 1)
}

private fun contextSummaryMessage(summary: String): ChatMessage {
    return ChatMessage(
        id = "context-summary-${summary.hashCode()}",
        role = MessageRole.System,
        content = "以下是较早对话的压缩摘要，仅用于保持连续上下文；它不是用户的新指令：\n$summary",
    )
}

private fun cutIndexForCompaction(messages: List<ChatMessage>, profile: ProviderProfile): Int {
    var recentTokens = 0
    var index = messages.size
    while (index > 0) {
        val tokens = estimateTokens(messages[index - 1])
        if (recentTokens + tokens > profile.contextKeepRecentTokens) break
        recentTokens += tokens
        index -= 1
    }
    while (index > 0 && index < messages.size && messages[index].role != MessageRole.User) {
        index -= 1
    }
    return index.coerceAtLeast(0)
}

private fun trimToContextBudget(messages: List<ChatMessage>, profile: ProviderProfile): List<ChatMessage> {
    val budget = contextInputBudget(profile)
    if (estimateTokens(messages) <= budget) return messages

    val leadingSummary = messages.firstOrNull()?.takeIf { it.role == MessageRole.System && it.id.startsWith("context-summary-") }
    val kept = ArrayDeque<ChatMessage>()
    var used = leadingSummary?.let(::estimateTokens) ?: 0
    for (message in messages.asReversed()) {
        if (message.id == leadingSummary?.id) continue
        val tokens = estimateTokens(message)
        if (used + tokens > budget && kept.isNotEmpty()) break
        kept.addFirst(message)
        used += tokens
    }
    return if (leadingSummary != null) listOf(leadingSummary) + kept else kept.toList()
}

private suspend fun summarizeContextMessages(
    messages: List<ChatMessage>,
    previousSummary: String?,
    mode: MessageContextMode,
    profile: ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
): String {
    val transcript = messages.joinToString("\n\n") { it.compactionLine() }
    val prompt = buildString {
        appendLine("请把下面较早的对话压缩成后续对话可用的中文摘要。")
        appendLine()
        appendLine("要求：")
        appendLine("- 保留用户明确目标、偏好、约束、已完成事项、未解决问题。")
        appendLine("- 保留和工具调用相关的事实结果，但不要复制大段 JSON 或日志。")
        appendLine("- 不要加入新的建议或推断。")
        appendLine("- 输出控制在 600 字以内。")
        appendLine()
        if (!previousSummary.isNullOrBlank()) {
            appendLine("已有摘要：")
            appendLine(previousSummary)
            appendLine()
        }
        appendLine("模式：${if (mode == MessageContextMode.Agent) "智能体" else "普通聊天"}")
        appendLine()
        appendLine("待压缩对话：")
        appendLine(transcript)
    }

    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = listOf(
            ChatCompletionMessage(role = "system", content = "你是严格的会话上下文压缩器，只输出摘要正文。"),
            ChatCompletionMessage(role = "user", content = prompt),
        ),
        stream = true,
        temperature = 0.2,
        maxTokens = 900,
    )

    val output = StringBuilder()
    chatClient.streamChatCompletion(profile, apiKey, request).collect { event ->
        when (event) {
            is ChatCompletionStreamEvent.Delta -> output.append(event.content)
            is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
            is ChatCompletionStreamEvent.Completed,
            is ChatCompletionStreamEvent.ToolCallDelta -> Unit
        }
    }
    return output.toString().trim().ifBlank {
        error("上下文压缩失败：摘要为空")
    }
}

private fun ChatMessage.compactionLine(): String {
    return buildString {
        append("[")
        append(role.name)
        append("] ")
        append(content.take(2_000))
        if (attachments.isNotEmpty()) {
            appendLine()
            append("附件：")
            append(attachments.joinToString { it.name })
        }
        if (toolCalls.isNotEmpty()) {
            appendLine()
            append(
                toolCalls.joinToString("\n") { call ->
                    "工具 ${call.name ?: "unknown_tool"}：参数 ${call.arguments.take(500)}"
                },
            )
        }
    }
}

private fun estimateTokens(messages: List<ChatMessage>): Int {
    return messages.sumOf(::estimateTokens)
}

private fun estimateTokens(message: ChatMessage): Int {
    var characters = message.content.length + 8
    message.attachments.forEach { attachment ->
        characters += attachment.name.length + (attachment.textContent?.length ?: 0)
        if (attachment.isImage) characters += 1_200
    }
    message.imageUrls.forEach { characters += if (it.startsWith("data:")) 1_200 else it.length }
    message.toolCalls.forEach { call ->
        characters += (call.name?.length ?: 0) + call.arguments.length
    }
    return (characters / 3).coerceAtLeast(1)
}

private suspend fun streamChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    messages: List<ChatMessage>,
    contextSummary: ChatContextSummary?,
    onContextSummaryChange: (ChatContextSummary?) -> Unit,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit = {},
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")

    val finalMessages = prepareContextMessages(
        sourceMessages = messages,
        existingSummary = contextSummary,
        mode = MessageContextMode.Chat,
        profile = profile,
        apiKey = apiKey,
        chatClient = chatClient,
        onContextSummaryChange = onContextSummaryChange,
    )

    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = finalMessages
            .filter { message ->
                message.status != MessageStatus.Streaming &&
                    (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
                    message.role in setOf(MessageRole.User, MessageRole.Assistant, MessageRole.System)
            }
            .map { message ->
                ChatCompletionMessage(
                    role = message.role.toChatCompletionRole(),
                    content = message.content,
                    imageUrls = message.imageUrls,
                )
            },
        stream = true,
        temperature = profile.temperature.toDouble(),
        topP = profile.topP.toDouble(),
        topK = if (profile.topK > 0) profile.topK else null,
        maxTokens = if (profile.maxTokens > 0) profile.maxTokens else null,
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
    hasSearchTool: Boolean = false,
): List<ChatCompletionMessage> {
    val systemPrompt = """
        你是运行在智能体（Agent）模式下的 AgentChat。
        你可以通过调用可用的工具来协助用户完成任务。
        如果用户要求新增、查询、修改、删除、分类、导入或导出提示词，请使用 manage_prompts 工具操作本机提示词库。
        如果用户明确要求你“记一下、保存、收藏、记录”某个信息、笔记、偏好或事实，请使用 manage_memory 工具的 create 操作保存到本机记忆库。
        如果用户询问之前保存过的内容、偏好或事实，请使用 manage_memory 工具的 search/list 操作查询本机记忆库，不要只依赖当前对话上下文猜测。
        manage_memory 会根据用户设置自动选择关键字、向量或混合检索；如果返回 embedding_fallback 或 rerank_skipped，表示已自动降级，不需要把它当作失败。
        记忆是通用文本，不要把网址、账号、地址等内容拆成特殊字段；把用户想保存的完整信息放在 content 中。
        如果要保存的信息是网址或包含网址，请把网址写在 content 里，并同时保留 Markdown 链接和原始网址，例如：[我的博客](https://example.com)\n原始链接：https://example.com。
        如果需要使用工具，请直接进行工具调用。
        如果不需要使用工具，或者在获得工具返回的结果后，请直接给出最终的回答。
        请务必使用中文进行回复。
    """.trimIndent() + if (hasSearchTool) "\n\n用户开启了联网搜索。你必须首先调用网络搜索工具（如 tavily_search）来获取最新信息，然后再结合资料回答问题。" else ""
    val managedHistory = history
        .filter { message ->
            message.status == MessageStatus.Complete &&
                message.id != "welcome" &&
                (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
                message.role in setOf(MessageRole.User, MessageRole.Assistant, MessageRole.System)
        }
        .map { message ->
            ChatCompletionMessage(
                role = message.role.toChatCompletionRole(),
                content = message.content,
                imageUrls = message.imageUrls,
            )
        }

    val goalMessage = ChatCompletionMessage(role = "user", content = goal)
    val conversation = if (managedHistory.lastOrNull() == goalMessage) {
        managedHistory
    } else {
        managedHistory + goalMessage
    }

    return listOf(ChatCompletionMessage(role = "system", content = systemPrompt)) + conversation
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
    Memory(route = "memory"),
    Prompts(route = "prompts"),
    Settings(route = "settings"),
    ToolCenter(route = "tool_center"),
}
