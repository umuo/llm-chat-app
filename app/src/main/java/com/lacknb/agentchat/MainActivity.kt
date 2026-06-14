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
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
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
import com.lacknb.agentchat.tool.ToolRegistry
import com.lacknb.agentchat.tool.PlanAgentTool
import com.lacknb.agentchat.tool.ReadFileTool
import com.lacknb.agentchat.tool.WriteFileTool
import com.lacknb.agentchat.tool.EditFileTool
import com.lacknb.agentchat.tool.ManageMemoryTool
import com.lacknb.agentchat.tool.ManagePromptsTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.core.view.WindowCompat

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
                ManagePromptsTool(promptRepository)
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
                        toolRegistry = toolRegistry,
                        goal = goal,
                        history = history,
                        onEvent = onEvent,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
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
    onEvent: (AgentEvent) -> Unit,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit,
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")
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
        summary = "智能体模式：根据是否有工具调用执行操作，无工具调用时直接回复并结束。",
    )

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

        emitEvent(
            type = AgentEventType.Action,
            summary = "正在使用 ${toolRegistry.size} 个可用工具调用 ${profile.defaultModel}。",
        )

        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = conversation,
            stream = true,
            tools = toolRegistry.getAllDeclarations(),
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

private suspend fun streamChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    messages: List<ChatMessage>,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit = {},
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")

    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = messages
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
    """.trimIndent()
    val recentHistory = history
        .filter { message ->
            message.status == MessageStatus.Complete &&
                message.id != "welcome" &&
                (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
                message.role in setOf(MessageRole.User, MessageRole.Assistant)
        }
        .takeLast(8)
        .map { message ->
            ChatCompletionMessage(
                role = message.role.toChatCompletionRole(),
                content = message.content,
                imageUrls = message.imageUrls,
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
}
